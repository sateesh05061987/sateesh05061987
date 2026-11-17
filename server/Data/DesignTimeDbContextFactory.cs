using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace RecordingServer.Data;

/// <summary>Lets `dotnet ef` create migrations without starting the web host (no API key / DB needed).</summary>
public class DesignTimeDbContextFactory : IDesignTimeDbContextFactory<AppDbContext>
{
    public AppDbContext CreateDbContext(string[] args)
    {
        var cs = Environment.GetEnvironmentVariable("ConnectionStrings__Default")
                 ?? "Server=localhost;port=3306;Database=CallRecordingsDB;user=callrec;password=design-time-only;";
        return new AppDbContext(new DbContextOptionsBuilder<AppDbContext>().UseMySql(cs, AppDbContext.ServerVersion).Options);
    }
}
