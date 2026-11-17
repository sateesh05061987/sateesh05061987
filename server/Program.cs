using Microsoft.AspNetCore.Http.Features;
using Microsoft.EntityFrameworkCore;
using RecordingServer.Data;
using RecordingServer.Endpoints;
using RecordingServer.Services;

var builder = WebApplication.CreateBuilder(args);

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

builder.Services.AddDbContext<AppDbContext>(o =>
    o.UseSqlServer(builder.Configuration.GetConnectionString("Default"),
        sql => sql.EnableRetryOnFailure()));

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
