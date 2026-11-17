namespace RecordingServer.Models;

public class Recording
{
    public long Id { get; set; }

    /// <summary>Original file name as sent by the device (sanitised).</summary>
    public string FileName { get; set; } = "";

    /// <summary>Path relative to the storage root, e.g. "2026/11/call_123.m4a".</summary>
    public string RelativePath { get; set; } = "";

    /// <summary>"in" or "out".</summary>
    public string Direction { get; set; } = "";

    public string? PhoneNumber { get; set; }

    public DateTimeOffset StartedAt { get; set; }

    public int DurationSeconds { get; set; }

    /// <summary>"CALL", "VREC" or "MIC".</summary>
    public string AudioSource { get; set; } = "";

    public string? DeviceName { get; set; }

    public long SizeBytes { get; set; }

    /// <summary>Lower-case hex SHA-256 of the file contents. Unique.</summary>
    public string FileHash { get; set; } = "";

    public DateTimeOffset UploadedAt { get; set; }
}
