using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace RecordingServer.Migrations
{
    /// <inheritdoc />
    public partial class InitialCreate : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "Recordings",
                columns: table => new
                {
                    Id = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("SqlServer:Identity", "1, 1"),
                    FileName = table.Column<string>(type: "nvarchar(255)", maxLength: 255, nullable: false),
                    RelativePath = table.Column<string>(type: "nvarchar(400)", maxLength: 400, nullable: false),
                    Direction = table.Column<string>(type: "nvarchar(3)", maxLength: 3, nullable: false),
                    PhoneNumber = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: true),
                    StartedAt = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false),
                    DurationSeconds = table.Column<int>(type: "int", nullable: false),
                    AudioSource = table.Column<string>(type: "nvarchar(8)", maxLength: 8, nullable: false),
                    DeviceName = table.Column<string>(type: "nvarchar(128)", maxLength: 128, nullable: true),
                    SizeBytes = table.Column<long>(type: "bigint", nullable: false),
                    FileHash = table.Column<string>(type: "nchar(64)", fixedLength: true, maxLength: 64, nullable: false),
                    UploadedAt = table.Column<DateTimeOffset>(type: "datetimeoffset", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Recordings", x => x.Id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_Recordings_FileHash",
                table: "Recordings",
                column: "FileHash",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Recordings_StartedAt",
                table: "Recordings",
                column: "StartedAt");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "Recordings");
        }
    }
}
