using MySqlConnector;

namespace RecordingServer.Endpoints;

public record KatavuUser(string Name, string Username, string? Role, string? Organization,
    bool IsActive, bool SignedIn, DateTime? LastLoginAt);
public record KatavuLogin(string Name, string? Role, string? Organization, string Action, DateTime At);
public record KatavuActivity(string? User, string Action, string Entity, string? Organization, DateTime At);

public record KatavuUsageDto(
    int TotalUsers, int ActiveUsers, int SignedIn, int LoginsToday, int Logins7d, int Organizations,
    IReadOnlyList<KatavuUser> Users,
    IReadOnlyList<KatavuLogin> RecentLogins,
    IReadOnlyList<KatavuActivity> RecentActivity);

/// <summary>
/// Reads KATAVU's own database (read-only user) and reports who uses KATAVU: users, their last
/// login and whether they're signed in, recent logins, and recent activity. Shares the /api key.
/// No passwords, tokens, emails or phone numbers are read.
/// </summary>
public static class KatavuUsageEndpoints
{
    public static async Task<IResult> Get(IConfiguration config, CancellationToken ct)
    {
        var cs = config.GetConnectionString("Katavu");
        if (string.IsNullOrWhiteSpace(cs))
            return Results.Problem(statusCode: 503, title: "KATAVU database access is not configured on the server.");

        await using var db = new MySqlConnection(cs);
        await db.OpenAsync(ct);

        var (totalUsers, activeUsers, signedIn, loginsToday, logins7d, orgs) = await Summary(db, ct);
        var users = await Users(db, ct);
        var logins = await RecentLogins(db, ct);
        var activity = await RecentActivity(db, ct);

        return Results.Ok(new KatavuUsageDto(
            totalUsers, activeUsers, signedIn, loginsToday, logins7d, orgs, users, logins, activity));
    }

    private static async Task<(int, int, int, int, int, int)> Summary(MySqlConnection db, CancellationToken ct)
    {
        const string sql = """
            SELECT
              (SELECT COUNT(*) FROM Users),
              (SELECT COUNT(*) FROM Users WHERE IsActive = 1),
              (SELECT COUNT(DISTINCT UserId) FROM UserSessions WHERE ExpiresAt > UTC_TIMESTAMP()),
              (SELECT COUNT(*) FROM LoginHistories WHERE Action = 'Login' AND CreatedAt >= UTC_DATE()),
              (SELECT COUNT(*) FROM LoginHistories WHERE Action = 'Login' AND CreatedAt >= UTC_DATE() - INTERVAL 7 DAY),
              (SELECT COUNT(*) FROM Organizations);
            """;
        await using var cmd = new MySqlCommand(sql, db);
        await using var r = await cmd.ExecuteReaderAsync(ct);
        await r.ReadAsync(ct);
        return (r.GetInt32(0), r.GetInt32(1), r.GetInt32(2), r.GetInt32(3), r.GetInt32(4), r.GetInt32(5));
    }

    private static async Task<List<KatavuUser>> Users(MySqlConnection db, CancellationToken ct)
    {
        const string sql = """
            SELECT u.Name, u.Username, r.Name AS Role, o.Name AS Org, u.IsActive, u.LastLoginAt,
                   EXISTS(SELECT 1 FROM UserSessions s WHERE s.UserId = u.Id AND s.ExpiresAt > UTC_TIMESTAMP()) AS SignedIn
            FROM Users u
            LEFT JOIN Roles r ON r.Id = u.RoleId
            LEFT JOIN Organizations o ON o.Id = u.OrganizationId
            ORDER BY u.LastLoginAt IS NULL, u.LastLoginAt DESC
            LIMIT 200;
            """;
        await using var cmd = new MySqlCommand(sql, db);
        await using var r = await cmd.ExecuteReaderAsync(ct);
        var list = new List<KatavuUser>();
        while (await r.ReadAsync(ct))
            list.Add(new KatavuUser(
                r.GetString(0), r.GetString(1), Str(r, 2), Str(r, 3),
                r.GetBoolean(4), r.GetInt64(6) == 1, Date(r, 5)));
        return list;
    }

    private static async Task<List<KatavuLogin>> RecentLogins(MySqlConnection db, CancellationToken ct)
    {
        const string sql = """
            SELECT l.Name, l.RoleName, o.Name AS Org, l.Action, l.CreatedAt
            FROM LoginHistories l
            LEFT JOIN Organizations o ON o.Id = l.OrganizationId
            ORDER BY l.CreatedAt DESC
            LIMIT 30;
            """;
        await using var cmd = new MySqlCommand(sql, db);
        await using var r = await cmd.ExecuteReaderAsync(ct);
        var list = new List<KatavuLogin>();
        while (await r.ReadAsync(ct))
            list.Add(new KatavuLogin(r.GetString(0), Str(r, 1), Str(r, 2), r.GetString(3), Utc(r, 4)));
        return list;
    }

    private static async Task<List<KatavuActivity>> RecentActivity(MySqlConnection db, CancellationToken ct)
    {
        const string sql = """
            SELECT u.Name AS UserName, a.Action, a.EntityName, o.Name AS Org, a.CreatedAt
            FROM AuditLogs a
            LEFT JOIN Users u ON u.Id = a.UserId
            LEFT JOIN Organizations o ON o.Id = a.OrganizationId
            ORDER BY a.CreatedAt DESC
            LIMIT 30;
            """;
        await using var cmd = new MySqlCommand(sql, db);
        await using var r = await cmd.ExecuteReaderAsync(ct);
        var list = new List<KatavuActivity>();
        while (await r.ReadAsync(ct))
            list.Add(new KatavuActivity(Str(r, 0), r.GetString(1), r.GetString(2), Str(r, 3), Utc(r, 4)));
        return list;
    }

    private static string? Str(MySqlDataReader r, int i) => r.IsDBNull(i) ? null : r.GetString(i);
    private static DateTime Utc(MySqlDataReader r, int i) => DateTime.SpecifyKind(r.GetDateTime(i), DateTimeKind.Utc);
    private static DateTime? Date(MySqlDataReader r, int i) => r.IsDBNull(i) ? null : DateTime.SpecifyKind(r.GetDateTime(i), DateTimeKind.Utc);
}
