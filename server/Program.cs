using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.EntityFrameworkCore;
using RecordingServer.Data;
using RecordingServer.Endpoints;
using RecordingServer.Services;

var builder = WebApplication.CreateBuilder(args);

// Defaults in code: the VPS package ships without appsettings*.json (config is in /etc/callrec/api.env).
builder.Logging.AddFilter("Microsoft.AspNetCore", LogLevel.Warning);
builder.Logging.AddFilter("Microsoft.EntityFrameworkCore", LogLevel.Warning);

builder.Services.Configure<StorageOptions>(builder.Configuration.GetSection(StorageOptions.Section));
builder.Services.Configure<AuthOptions>(builder.Configuration.GetSection(AuthOptions.Section));

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

app.UseForwardedHeaders();
app.UseExceptionHandler();
app.UseStatusCodePages();

if (app.Configuration.GetValue("UseHttpsRedirection", false))
{
    app.UseHsts();
    app.UseHttpsRedirection();
}

app.MapHealthChecks("/healthz");
app.MapRecordingEndpoints();

app.Run();
