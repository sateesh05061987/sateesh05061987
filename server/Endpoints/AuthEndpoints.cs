using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.Extensions.Options;
using RecordingServer.Services;

namespace RecordingServer.Endpoints;

public record LoginRequest(string? Username, string? Password);

/// <summary>Cookie login for the web app at "/".</summary>
public static class AuthEndpoints
{
    public const string LoginRateLimit = "login";

    public static void MapAuthEndpoints(this IEndpointRouteBuilder app)
    {
        var auth = app.MapGroup("/auth");

        auth.MapPost("/login", async (LoginRequest body, HttpContext http, IOptions<AdminOptions> admin,
            ILoggerFactory loggers) =>
        {
            var opts = admin.Value;
            var log = loggers.CreateLogger("Login");
            if (!opts.IsConfigured)
                return Results.Problem(statusCode: 503, title: "Web login is not configured on the server.");

            var username = body.Username?.Trim() ?? "";
            var password = body.Password ?? "";
            // Always run the hash, so a wrong username takes as long as a wrong password.
            var passwordOk = PasswordHashing.Verify(password, opts.PasswordHash);
            var userOk = PasswordHashing.FixedTimeEquals(username.ToLowerInvariant(), opts.Username.ToLowerInvariant());
            if (!passwordOk || !userOk)
            {
                log.LogWarning("Failed web login for '{User}' from {Ip}", username, http.Connection.RemoteIpAddress);
                return Results.Problem(statusCode: 401, title: "Wrong username or password.");
            }

            var identity = new ClaimsIdentity([new Claim(ClaimTypes.Name, opts.Username)],
                CookieAuthenticationDefaults.AuthenticationScheme);
            await http.SignInAsync(CookieAuthenticationDefaults.AuthenticationScheme, new ClaimsPrincipal(identity));
            log.LogInformation("Web login for '{User}' from {Ip}", opts.Username, http.Connection.RemoteIpAddress);
            return Results.Ok(new { username = opts.Username });
        }).RequireRateLimiting(LoginRateLimit);

        auth.MapPost("/logout", async (HttpContext http) =>
        {
            await http.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
            return Results.NoContent();
        });

        auth.MapGet("/me", (HttpContext http) => http.User.Identity?.IsAuthenticated == true
            ? Results.Ok(new { username = http.User.Identity.Name })
            : Results.Problem(statusCode: 401, title: "Not signed in."));
    }
}
