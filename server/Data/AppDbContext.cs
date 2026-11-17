using Microsoft.EntityFrameworkCore;
using RecordingServer.Models;

namespace RecordingServer.Data;

public class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
    /// <summary>Fixed rather than auto-detected, so design-time tools need no database (as in KATAVU).</summary>
    public static readonly MySqlServerVersion ServerVersion = new(new Version(8, 0, 33));

    public DbSet<Recording> Recordings => Set<Recording>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        var r = modelBuilder.Entity<Recording>();
        r.ToTable("Recordings");
        r.HasKey(x => x.Id);
        r.Property(x => x.FileName).HasMaxLength(255).IsRequired();
        r.Property(x => x.RelativePath).HasMaxLength(400).IsRequired();
        r.Property(x => x.Direction).HasMaxLength(3).IsRequired();
        r.Property(x => x.PhoneNumber).HasMaxLength(64);
        r.Property(x => x.AudioSource).HasMaxLength(8).IsRequired();
        r.Property(x => x.DeviceName).HasMaxLength(128);
        r.Property(x => x.FileHash).HasMaxLength(64).IsFixedLength().IsRequired().HasCharSet("ascii");
        r.HasIndex(x => x.FileHash).IsUnique();
        r.HasIndex(x => x.StartedAt);

        // MySQL has no offset-aware type: Pomelo stores DateTimeOffset as UTC datetime(6),
        // so StartedAt / UploadedAt come back with a +00:00 offset.
    }
}
