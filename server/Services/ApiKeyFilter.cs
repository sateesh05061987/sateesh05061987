using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;

namespace RecordingServer.Services;

/// <summary>
/// Lets a request through when it carries the right X-Api-Key (the phone app) or a signed-in
/// web-app cookie. Cookie requests that change data must also send X-Requested-With, which a
/// cross-site form or link cannot add (on top of the SameSite=Strict cookie).
/// </summary>
public class ApiKeyFilter(IOptions<AuthOptions> options) : IEndpointFilter
{
    public const string HeaderName = "X-Api-Key";
    public const string CsrfHeader = "X-Requested-With";

    private readonly byte[] _expected = Encoding.UTF8.GetBytes(options.Value.ApiKey);

    public ValueTask<object?> InvokeAsync(EndpointFilterInvocationContext context, EndpointFilterDelegate next)
    {
        var http = context.HttpContext;
        var provided = http.Request.Headers[HeaderName].ToString();

        if (provided.Length > 0)
        {
            // FixedTimeEquals returns false immediately on length mismatch; that only leaks the length.
            if (_expected.Length > 0 && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(provided), _expected))
                return next(context);
            return Unauthorized("Missing or invalid API key.");
        }

        if (http.User.Identity?.IsAuthenticated == true)
        {
            var safe = HttpMethods.IsGet(http.Request.Method) || HttpMethods.IsHead(http.Request.Method);
            if (!safe && string.IsNullOrEmpty(http.Request.Headers[CsrfHeader]))
            {
                return ValueTask.FromResult<object?>(Results.Problem(
                    statusCode: StatusCodes.Status403Forbidden, title: $"Missing {CsrfHeader} header."));
            }
            return next(context);
        }

        return Unauthorized("Missing or invalid API key.");
    }

    private static ValueTask<object?> Unauthorized(string title) =>
        ValueTask.FromResult<object?>(Results.Problem(statusCode: StatusCodes.Status401Unauthorized, title: title));
}
