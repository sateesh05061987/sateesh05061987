using Microsoft.EntityFrameworkCore;
using RecordingServer.Data;

namespace RecordingServer.Endpoints;

public record DayCount(string Date, int Count, long DurationSeconds);
public record NumberCount(string PhoneNumber, int Count, long DurationSeconds);

public record StatsDto(
    int TotalCount,
    long TotalDurationSeconds,
    long TotalBytes,
    int Incoming,
    int Outgoing,
    int ThisMonthCount,
    long ThisMonthDurationSeconds,
    DateTimeOffset? FirstStartedAt,
    DateTimeOffset? LastStartedAt,
    IReadOnlyList<DayCount> PerDay,
    IReadOnlyList<NumberCount> TopNumbers);

public static class StatsEndpoints
{
    /// <summary>Usage summary for the phone dashboard and the web app. Shares the /api key filter.</summary>
    public static async Task<IResult> Get(AppDbContext db, CancellationToken ct)
    {
        var all = db.Recordings.AsNoTracking();

        var total = await all.CountAsync(ct);
        if (total == 0)
        {
            return Results.Ok(new StatsDto(0, 0, 0, 0, 0, 0, 0, null, null, [], []));
        }

        var totalDuration = await all.SumAsync(r => (long)r.DurationSeconds, ct);
        var totalBytes = await all.SumAsync(r => r.SizeBytes, ct);
        var incoming = await all.CountAsync(r => r.Direction == "in", ct);
        var firstAt = await all.MinAsync(r => (DateTimeOffset?)r.StartedAt, ct);
        var lastAt = await all.MaxAsync(r => (DateTimeOffset?)r.StartedAt, ct);

        var monthStart = new DateTimeOffset(DateTime.UtcNow.Year, DateTime.UtcNow.Month, 1, 0, 0, 0, TimeSpan.Zero);
        var thisMonth = all.Where(r => r.StartedAt >= monthStart);
        var thisMonthCount = await thisMonth.CountAsync(ct);
        var thisMonthDuration = thisMonthCount == 0 ? 0 : await thisMonth.SumAsync(r => (long)r.DurationSeconds, ct);

        // Last 30 days grouped per calendar day (UTC). Small set, so grouped in memory.
        var since = DateTimeOffset.UtcNow.Date.AddDays(-29);
        var recent = await all.Where(r => r.StartedAt >= since)
            .Select(r => new { r.StartedAt, r.DurationSeconds })
            .ToListAsync(ct);
        var byDay = recent
            .GroupBy(r => r.StartedAt.UtcDateTime.Date)
            .ToDictionary(g => g.Key, g => (Count: g.Count(), Dur: g.Sum(x => (long)x.DurationSeconds)));
        var perDay = Enumerable.Range(0, 30).Select(i =>
        {
            var day = DateTime.UtcNow.Date.AddDays(-29 + i);
            var hit = byDay.TryGetValue(day, out var v);
            return new DayCount(day.ToString("yyyy-MM-dd"), hit ? v.Count : 0, hit ? v.Dur : 0);
        }).ToList();

        // Group + sum in SQL with int (no cast — Pomelo can't translate a cast inside GroupBy),
        // then widen to long in memory. Per-number totals can't overflow int here.
        var topRaw = await all
            .Where(r => r.PhoneNumber != null && r.PhoneNumber != "")
            .GroupBy(r => r.PhoneNumber!)
            .Select(g => new { Phone = g.Key, Count = g.Count(), Dur = g.Sum(x => x.DurationSeconds) })
            .OrderByDescending(x => x.Count).ThenByDescending(x => x.Dur)
            .Take(5)
            .ToListAsync(ct);
        var topNumbers = topRaw.Select(x => new NumberCount(x.Phone, x.Count, x.Dur)).ToList();

        return Results.Ok(new StatsDto(
            total, totalDuration, totalBytes, incoming, total - incoming,
            thisMonthCount, thisMonthDuration, firstAt, lastAt, perDay, topNumbers));
    }
}
