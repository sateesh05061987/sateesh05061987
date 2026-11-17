using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;

namespace RecordingServer.Services;

/// <summary>Rejects requests whose X-Api-Key header does not match Auth:ApiKey.</summary>
public class ApiKeyFilter(IOptions<AuthOptions> options) : IEndpointFilter
{
    public const string HeaderName = "X-Api-Key";

    private readonly byte[] _expected = Encoding.UTF8.GetBytes(options.Value.ApiKey);

    public ValueTask<object?> InvokeAsync(EndpointFilterInvocationContext context, EndpointFilterDelegate next)
    {
        var provided = context.HttpContext.Request.Headers[HeaderName].ToString();
        var providedBytes = Encoding.UTF8.GetBytes(provided);

        // FixedTimeEquals returns false immediately on length mismatch; that only leaks the length.
        if (_expected.Length == 0 || !CryptographicOperations.FixedTimeEquals(providedBytes, _expected))
        {
            return ValueTask.FromResult<object?>(Results.Problem(
                statusCode: StatusCodes.Status401Unauthorized,
                title: "Missing or invalid API key."));
        }

        return next(context);
    }
}
