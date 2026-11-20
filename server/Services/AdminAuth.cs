using System.Security.Cryptography;
using System.Text;

namespace RecordingServer.Services;

/// <summary>The single web-app login. Set in /etc/callrec/api.env as Admin__Username / Admin__PasswordHash.</summary>
public class AdminOptions
{
    public const string Section = "Admin";

    public string Username { get; set; } = "";

    /// <summary>Output of <c>RecordingServer --hash-password</c>: pbkdf2-sha256$iterations$salt$hash.</summary>
    public string PasswordHash { get; set; } = "";

    public bool IsConfigured => Username.Length > 0 && PasswordHash.StartsWith("pbkdf2-sha256$", StringComparison.Ordinal);
}

public static class PasswordHashing
{
    private const int Iterations = 210_000;
    private const int SaltBytes = 16;
    private const int HashBytes = 32;

    public static string Hash(string password)
    {
        var salt = RandomNumberGenerator.GetBytes(SaltBytes);
        var hash = Rfc2898DeriveBytes.Pbkdf2(password, salt, Iterations, HashAlgorithmName.SHA256, HashBytes);
        return $"pbkdf2-sha256${Iterations}${Convert.ToBase64String(salt)}${Convert.ToBase64String(hash)}";
    }

    public static bool Verify(string password, string stored)
    {
        var parts = stored.Split('$');
        if (parts.Length != 4 || parts[0] != "pbkdf2-sha256" || !int.TryParse(parts[1], out var iterations))
            return false;
        try
        {
            var salt = Convert.FromBase64String(parts[2]);
            var expected = Convert.FromBase64String(parts[3]);
            var actual = Rfc2898DeriveBytes.Pbkdf2(password, salt, iterations, HashAlgorithmName.SHA256, expected.Length);
            return CryptographicOperations.FixedTimeEquals(actual, expected);
        }
        catch (FormatException)
        {
            return false;
        }
    }

    public static bool FixedTimeEquals(string a, string b) =>
        CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(a), Encoding.UTF8.GetBytes(b));
}
