using System.Security.Cryptography;
using Microsoft.Extensions.Options;

namespace RecordingServer.Services;

/// <summary>File-system storage for recordings, organised as {root}/yyyy/MM/{file}.</summary>
public class RecordingStorage
{
    private readonly string _root;

    public RecordingStorage(IOptions<StorageOptions> options, IWebHostEnvironment env)
    {
        var configured = options.Value.RootPath;
        _root = Path.GetFullPath(Path.IsPathRooted(configured)
            ? configured
            : Path.Combine(env.ContentRootPath, configured));
        Directory.CreateDirectory(_root);
        Directory.CreateDirectory(TempDir);
    }

    private string TempDir => Path.Combine(_root, ".incoming");

    /// <summary>Copies the upload to a temp file and computes its SHA-256 on the way.</summary>
    public async Task<(string TempPath, string Sha256, long Size)> SaveToTempAsync(Stream source, CancellationToken ct)
    {
        var tempPath = Path.Combine(TempDir, Guid.NewGuid().ToString("N") + ".part");
        using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        long size = 0;
        await using (var target = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                         81920, useAsync: true))
        {
            var buffer = new byte[81920];
            int read;
            while ((read = await source.ReadAsync(buffer, ct)) > 0)
            {
                sha.AppendData(buffer, 0, read);
                await target.WriteAsync(buffer.AsMemory(0, read), ct);
                size += read;
            }
        }
        return (tempPath, Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant(), size);
    }

    /// <summary>Moves a temp file into its final yyyy/MM folder. Returns the path relative to the root.</summary>
    public string Commit(string tempPath, string fileName, DateTimeOffset startedAt)
    {
        var utc = startedAt.UtcDateTime;
        var relDir = Path.Combine(utc.ToString("yyyy"), utc.ToString("MM"));
        var dir = Path.Combine(_root, relDir);
        Directory.CreateDirectory(dir);

        var name = Path.GetFileNameWithoutExtension(fileName);
        var ext = Path.GetExtension(fileName);
        // Reserve the name with CreateNew (atomic O_EXCL), then rename over the empty placeholder.
        // File.Move(overwrite: false) alone is check-then-rename and races with concurrent uploads.
        for (var i = 0; i < 1000; i++)
        {
            var candidate = i == 0 ? fileName : $"{name}_{i}{ext}";
            var target = Path.Combine(dir, candidate);
            try
            {
                using (new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None)) { }
            }
            catch (IOException) when (File.Exists(target))
            {
                continue; // name taken
            }
            File.Move(tempPath, target, overwrite: true);
            return Path.Combine(relDir, candidate).Replace('\\', '/');
        }
        throw new IOException($"Could not find a free file name for {fileName} in {relDir}.");
    }

    /// <summary>Resolves a stored relative path, refusing anything outside the root.</summary>
    public string? Resolve(string relativePath)
    {
        var full = Path.GetFullPath(Path.Combine(_root, relativePath));
        return full.StartsWith(_root + Path.DirectorySeparatorChar, StringComparison.Ordinal) ? full : null;
    }

    public void TryDelete(string? path)
    {
        if (path is null) return;
        try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    /// <summary>Checks the MP4 container signature: bytes 4-7 must be "ftyp".</summary>
    public static async Task<bool> LooksLikeMp4Async(string path, CancellationToken ct)
    {
        var header = new byte[8];
        await using var fs = File.OpenRead(path);
        var read = await fs.ReadAtLeastAsync(header, header.Length, throwOnEndOfStream: false, ct);
        return read == 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
    }
}
