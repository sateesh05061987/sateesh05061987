using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.EntityFrameworkCore;
using RecordingServer.Data;
using RecordingServer.Endpoints;
using RecordingServer.Services;

// One-off: `RecordingServer --hash-password` reads a password from stdin and prints the hash
// for Admin__PasswordHash, without starting the web server or needing any config.
if (args.Contains("--hash-password"))
{
    var password = Console.In.ReadLine() ?? "";
    if (password.Length < 10)
    {
        Console.Error.WriteLine("Password must be at least 10 characters.");
        return 1;
    }
    Console.WriteLine(PasswordHashing.Hash(password));
    return 0;
}

var builder = WebApplication.CreateBuilder(args);

// Defaults in code: the VPS package ships without appsettings*.json (config is in /etc/callrec/api.env).
builder.Logging.AddFilter("Microsoft.AspNetCore", LogLevel.Warning);
builder.Logging.AddFilter("Microsoft.EntityFrameworkCore", LogLevel.Warning);

builder.Services.Configure<StorageOptions>(builder.Configuration.GetSection(StorageOptions.Section));
builder.Services.Configure<AuthOptions>(builder.Configuration.GetSection(AuthOptions.Section));
builder.Services.Configure<AdminOptions>(builder.Configuration.GetSection(AdminOptions.Section));

var storageOptions = builder.Configuration.GetSection(StorageOptions.Section).Get<StorageOptions>() ?? new();
var apiKey = builder.Configuration[$"{AuthOptions.Section}:ApiKey"];
if (string.IsNullOrWhiteSpace(apiKey) || apiKey.Length < 24 || apiKey.StartsWith("CHANGE", StringComparison.OrdinalIgnoreCase))
{
    throw new InvalidOperationException(
        "Auth:ApiKey must be set to a random secret of at least 24 characters " +
        "(appsettings, user-secrets or the Auth__ApiKey environment variable).");
}

// Size limits: whole request a little above the file limit to leave room for the form fields.
var requestLimit = storageOptions.MaxUploadBytes + 1024 * 1024;
builder.WebHost.ConfigureKestrel(k => k.Limits.MaxRequestBodySize = requestLimit);
builder.Services.Configure<FormOptions>(o =>
{
    o.MultipartBodyLengthLimit = requestLimit;
    o.ValueLengthLimit = 4 * 1024;
    o.ValueCountLimit = 32;
});

var connectionString = builder.Configuration.GetConnectionString("Default");
if (string.IsNullOrWhiteSpace(connectionString))
    throw new InvalidOperationException("ConnectionStrings:Default is not configured.");

// Same provider and settings as KATAVU on the LilliBlush VPS (MySQL 8 via Pomelo).
builder.Services.AddDbContext<AppDbContext>(o =>
    o.UseMySql(connectionString, AppDbContext.ServerVersion,
        mysql => mysql.EnableRetryOnFailure(3, TimeSpan.FromSeconds(5), null)));

// nginx on the same machine terminates HTTPS and forwards the real client address and scheme.
builder.Services.Configure<ForwardedHeadersOptions>(o =>
    o.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto);

// ── Web-app login ────────────────────────────────────────────────────
// Cookie keys are kept on disk (not in the publish folder, which every deploy replaces),
// so a restart or deploy doesn't sign everyone out.
var storageRoot = Path.IsPathRooted(storageOptions.RootPath)
    ? storageOptions.RootPath
    : Path.Combine(builder.Environment.ContentRootPath, storageOptions.RootPath);
var keysPath = builder.Configuration["DataProtection:KeysPath"] ?? Path.Combine(storageRoot, ".keys");
Directory.CreateDirectory(keysPath);
if (!OperatingSystem.IsWindows())
    File.SetUnixFileMode(keysPath, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
builder.Services.AddDataProtection()
    .SetApplicationName("callrec")
    .PersistKeysToFileSystem(new DirectoryInfo(keysPath));

builder.Services.AddAuthentication(CookieAuthenticationDefaults.AuthenticationScheme)
    .AddCookie(o =>
    {
        o.Cookie.Name = "callrec_auth";
        o.Cookie.HttpOnly = true;
        o.Cookie.SameSite = SameSiteMode.Strict;
        o.Cookie.SecurePolicy = CookieSecurePolicy.SameAsRequest; // https behind nginx (forwarded proto)
        o.ExpireTimeSpan = TimeSpan.FromHours(12);
        o.SlidingExpiration = true;
        // This is an API + single page: answer 401/403 instead of redirecting to a login URL.
        o.Events.OnRedirectToLogin = ctx => { ctx.Response.StatusCode = 401; return Task.CompletedTask; };
        o.Events.OnRedirectToAccessDenied = ctx => { ctx.Response.StatusCode = 403; return Task.CompletedTask; };
    });
builder.Services.AddAuthorization();

// 10 login attempts per 15 minutes per client address, to slow password guessing.
builder.Services.AddRateLimiter(o =>
{
    o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    o.AddPolicy(AuthEndpoints.LoginRateLimit, ctx => RateLimitPartition.GetFixedWindowLimiter(
        ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = 10, Window = TimeSpan.FromMinutes(15) }));
});

builder.Services.AddSingleton<RecordingStorage>();
builder.Services.AddSingleton<ApiKeyFilter>();
builder.Services.AddProblemDetails();
builder.Services.AddHealthChecks();

var app = builder.Build();

if (app.Configuration.GetValue("Database:MigrateOnStartup", false))
{
    using var scope = app.Services.CreateScope();
    scope.ServiceProvider.GetRequiredService<AppDbContext>().Database.Migrate();
}

if (!app.Services.GetRequiredService<Microsoft.Extensions.Options.IOptions<AdminOptions>>().Value.IsConfigured)
    app.Logger.LogWarning("Admin__Username / Admin__PasswordHash are not set: the web app login is disabled.");

app.UseForwardedHeaders();
app.UseExceptionHandler();
app.UseStatusCodePages();

if (app.Configuration.GetValue("UseHttpsRedirection", false))
{
    app.UseHsts();
    app.UseHttpsRedirection();
}

// The web app (wwwroot/index.html) at "/".
app.Use(async (ctx, next) =>
{
    ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
    ctx.Response.Headers["X-Frame-Options"] = "DENY";
    ctx.Response.Headers["Referrer-Policy"] = "no-referrer";
    await next();
});
app.UseDefaultFiles();
app.UseStaticFiles(new StaticFileOptions
{
    OnPrepareResponse = ctx => ctx.Context.Response.Headers.CacheControl = "no-cache",
});

app.UseAuthentication();
app.UseRateLimiter();
app.UseAuthorization();

app.MapHealthChecks("/healthz");
app.MapAuthEndpoints();
app.MapRecordingEndpoints();

app.Run();
return 0;
