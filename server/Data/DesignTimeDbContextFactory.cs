using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace RecordingServer.Data;

/// <summary>Lets `dotnet ef` create migrations without starting the web host (no API key / DB needed).</summary>
public class DesignTimeDbContextFactory : IDesignTimeDbContextFactory<AppDbContext>
{
    public AppDbContext CreateDbContext(string[] args)
    {
        var cs = Environment.GetEnvironmentVariable("ConnectionStrings__Default")
                 ?? "Server=localhost,1433;Database=CallRecordings;Trusted_Connection=True;TrustServerCertificate=True";
        return new AppDbContext(new DbContextOptionsBuilder<AppDbContext>().UseSqlServer(cs).Options);
    }
}
