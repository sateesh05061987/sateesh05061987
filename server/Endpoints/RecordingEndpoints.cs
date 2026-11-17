using System.Globalization;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using RecordingServer.Data;
using RecordingServer.Models;
using RecordingServer.Services;

namespace RecordingServer.Endpoints;

public record RecordingDto(
    long Id, string FileName, string Direction, string? PhoneNumber, DateTimeOffset StartedAt,
    int DurationSeconds, string AudioSource, string? DeviceName, long SizeBytes, DateTimeOffset UploadedAt)
{
    public static RecordingDto From(Recording r) => new(r.Id, r.FileName, r.Direction, r.PhoneNumber, r.StartedAt,
        r.DurationSeconds, r.AudioSource, r.DeviceName, r.SizeBytes, r.UploadedAt);
}

public record PagedResult<T>(IReadOnlyList<T> Items, int Page, int PageSize, int TotalCount);

public static class RecordingEndpoints
{
    private static readonly string[] Directions = ["in", "out"];
    private static readonly string[] AudioSources = ["CALL", "VREC", "MIC"];

    public static void MapRecordingEndpoints(this IEndpointRouteBuilder app)
    {
        var api = app.MapGroup("/api").AddEndpointFilter<ApiKeyFilter>();

        // Used by the app's "Test connection" button: proves URL, TLS and API key are all good.
        api.MapGet("/ping", () => Results.Ok(new { status = "ok", serverTime = DateTimeOffset.UtcNow }));

        var recordings = api.MapGroup("/recordings");
        recordings.MapPost("/", Upload);
        recordings.MapGet("/", List);
        recordings.MapGet("/{id:long}", Get);
        recordings.MapGet("/{id:long}/audio", Audio);
        recordings.MapDelete("/{id:long}", Delete);
    }

    private static async Task<IResult> Upload(
        HttpRequest request, AppDbContext db, RecordingStorage storage,
        IOptions<StorageOptions> storageOptions, ILoggerFactory loggerFactory, CancellationToken ct)
    {
        var log = loggerFactory.CreateLogger("Upload");
        var opts = storageOptions.Value;

        if (!request.HasFormContentType)
            return Results.Problem(statusCode: 415, title: "Expected multipart/form-data.");

        IFormCollection form;
        try
        {
            form = await request.ReadFormAsync(ct);
        }
        catch (Exception ex) when (ex is InvalidDataException or BadHttpRequestException)
        {
            // Thrown when the body exceeds the configured multipart / request size limits.
            return Results.Problem(statusCode: 413, title: "Upload too large or malformed.", detail: ex.Message);
        }

        var errors = new Dictionary<string, string[]>();
        var file = form.Files.GetFile("file");
        if (file is null || file.Length == 0) errors["file"] = ["A non-empty 'file' part is required."];

        var fileName = SanitizeFileName(file?.FileName);
        var ext = Path.GetExtension(fileName).ToLowerInvariant();
        if (file is not null && !opts.EffectiveExtensions.Contains(ext, StringComparer.OrdinalIgnoreCase))
            errors["file"] = [$"Only {string.Join(", ", opts.EffectiveExtensions)} files are accepted."];
        if (file is not null && file.Length > opts.MaxUploadBytes)
            return Results.Problem(statusCode: 413, title: $"File exceeds {opts.MaxUploadBytes} bytes.");

        var direction = form["direction"].ToString().ToLowerInvariant();
        if (!Directions.Contains(direction)) errors["direction"] = ["Must be 'in' or 'out'."];

        var audioSource = form["audioSource"].ToString().ToUpperInvariant();
        if (!AudioSources.Contains(audioSource)) errors["audioSource"] = ["Must be CALL, VREC or MIC."];

        if (!DateTimeOffset.TryParse(form["startedAt"], CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal, out var startedAt))
            errors["startedAt"] = ["Must be an ISO 8601 timestamp."];

        if (!int.TryParse(form["durationSeconds"], NumberStyles.Integer, CultureInfo.InvariantCulture,
                out var duration) || duration < 0)
            errors["durationSeconds"] = ["Must be a non-negative integer."];

        var phone = Truncate(form["phoneNumber"].ToString(), 64);
        var device = Truncate(form["deviceName"].ToString(), 128);

        if (errors.Count > 0) return Results.ValidationProblem(errors);

        string? tempPath = null;
        string? finalPath = null;
        try
        {
            await using (var stream = file!.OpenReadStream())
            {
                (tempPath, var hash, var size) = await storage.SaveToTempAsync(stream, ct);

                if (!await RecordingStorage.LooksLikeMp4Async(tempPath, ct))
                {
                    return Results.ValidationProblem(new Dictionary<string, string[]>
                    {
                        ["file"] = ["File content is not an MP4/M4A container."]
                    });
                }

                var existing = await db.Recordings.AsNoTracking()
                    .Where(r => r.FileHash == hash).Select(r => (long?)r.Id).FirstOrDefaultAsync(ct);
                if (existing is not null)
                    return Duplicate(existing.Value);

                var relPath = storage.Commit(tempPath, fileName, startedAt);
                tempPath = null;
                finalPath = storage.Resolve(relPath);

                var rec = new Recording
                {
                    FileName = fileName,
                    RelativePath = relPath,
                    Direction = direction,
                    PhoneNumber = phone,
                    StartedAt = startedAt,
                    DurationSeconds = duration,
                    AudioSource = audioSource,
                    DeviceName = device,
                    SizeBytes = size,
                    FileHash = hash,
                    UploadedAt = DateTimeOffset.UtcNow,
                };
                db.Recordings.Add(rec);
                try
                {
                    await db.SaveChangesAsync(ct);
                }
                catch (DbUpdateException)
                {
                    // Lost a race with a concurrent upload of the same file: the unique index fired.
                    db.ChangeTracker.Clear();
                    var winner = await db.Recordings.AsNoTracking()
                        .Where(r => r.FileHash == hash).Select(r => (long?)r.Id).FirstOrDefaultAsync(ct);
                    if (winner is null) throw;
                    storage.TryDelete(finalPath);
                    return Duplicate(winner.Value);
                }

                finalPath = null; // committed; keep the file
                log.LogInformation("Stored recording {Id} ({Size} bytes) at {Path}", rec.Id, size, relPath);
                return Results.Created($"/api/recordings/{rec.Id}", RecordingDto.From(rec));
            }
        }
        finally
        {
            storage.TryDelete(tempPath);
            storage.TryDelete(finalPath);
        }
    }

    private static IResult Duplicate(long existingId) => Results.Json(
        new { title = "Duplicate recording (same SHA-256 already stored).", existingId },
        statusCode: StatusCodes.Status409Conflict);

    private static async Task<IResult> List(AppDbContext db, int page = 1, int pageSize = 50,
        string? phoneNumber = null, CancellationToken ct = default)
    {
        page = Math.Max(page, 1);
        pageSize = Math.Clamp(pageSize, 1, 200);

        var query = db.Recordings.AsNoTracking();
        if (!string.IsNullOrWhiteSpace(phoneNumber))
            query = query.Where(r => r.PhoneNumber != null && r.PhoneNumber.Contains(phoneNumber));

        var total = await query.CountAsync(ct);
        var items = await query
            .OrderByDescending(r => r.StartedAt).ThenByDescending(r => r.Id)
            .Skip((page - 1) * pageSize).Take(pageSize)
            .ToListAsync(ct);

        return Results.Ok(new PagedResult<RecordingDto>(items.Select(RecordingDto.From).ToList(), page, pageSize, total));
    }

    private static async Task<IResult> Get(long id, AppDbContext db, CancellationToken ct)
    {
        var rec = await db.Recordings.AsNoTracking().FirstOrDefaultAsync(r => r.Id == id, ct);
        return rec is null ? Results.NotFound() : Results.Ok(RecordingDto.From(rec));
    }

    private static async Task<IResult> Audio(long id, AppDbContext db, RecordingStorage storage, CancellationToken ct)
    {
        var rec = await db.Recordings.AsNoTracking().FirstOrDefaultAsync(r => r.Id == id, ct);
        if (rec is null) return Results.NotFound();

        var path = storage.Resolve(rec.RelativePath);
        if (path is null || !File.Exists(path))
            return Results.Problem(statusCode: 410, title: "Audio file is missing from storage.");

        // Range support lets browsers / players seek without downloading the whole file.
        return Results.File(path, "audio/mp4", rec.FileName, enableRangeProcessing: true);
    }

    private static async Task<IResult> Delete(long id, AppDbContext db, RecordingStorage storage, CancellationToken ct)
    {
        var rec = await db.Recordings.FirstOrDefaultAsync(r => r.Id == id, ct);
        if (rec is null) return Results.NotFound();

        db.Recordings.Remove(rec);
        await db.SaveChangesAsync(ct);
        storage.TryDelete(storage.Resolve(rec.RelativePath));
        return Results.NoContent();
    }

    private static string SanitizeFileName(string? raw)
    {
        var name = Path.GetFileName((raw ?? "").Replace('\\', '/'));
        var invalid = Path.GetInvalidFileNameChars();
        name = new string(name.Select(c => invalid.Contains(c) || char.IsControl(c) ? '_' : c).ToArray()).Trim();
        if (name.StartsWith('.')) name = "_" + name;
        if (string.IsNullOrEmpty(name)) name = "recording.m4a";
        if (name.Length > 200)
        {
            var ext = Path.GetExtension(name);
            name = name[..(200 - ext.Length)] + ext;
        }
        return name;
    }

    private static string? Truncate(string value, int max)
    {
        value = value.Trim();
        if (value.Length == 0) return null;
        return value.Length <= max ? value : value[..max];
    }
}
