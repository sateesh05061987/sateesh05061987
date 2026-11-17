using Microsoft.EntityFrameworkCore;
using RecordingServer.Models;

namespace RecordingServer.Data;

public class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
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
        r.Property(x => x.FileHash).HasMaxLength(64).IsFixedLength().IsRequired();
        r.HasIndex(x => x.FileHash).IsUnique();
        r.HasIndex(x => x.StartedAt);
    }
}
