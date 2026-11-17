namespace RecordingServer.Services;

public class StorageOptions
{
    public const string Section = "Storage";

    /// <summary>Root folder for audio files. Files go into {RootPath}/yyyy/MM/.</summary>
    public string RootPath { get; set; } = "recordings";

    /// <summary>Maximum accepted upload size for a single recording, in bytes.</summary>
    public long MaxUploadBytes { get; set; } = 200L * 1024 * 1024;

    /// <summary>Allowed file extensions. Empty in config means ".m4a" only.</summary>
    /// <remarks>Not pre-filled: the config binder appends to arrays instead of replacing them.</remarks>
    public string[] AllowedExtensions { get; set; } = [];

    public string[] EffectiveExtensions => AllowedExtensions.Length > 0 ? AllowedExtensions : [".m4a"];
}

public class AuthOptions
{
    public const string Section = "Auth";

    public string ApiKey { get; set; } = "";
}
