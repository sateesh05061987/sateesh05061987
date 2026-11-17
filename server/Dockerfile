# syntax=docker/dockerfile:1
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY RecordingServer.csproj ./
RUN dotnet restore RecordingServer.csproj
COPY . .
RUN dotnet publish RecordingServer.csproj -c Release -o /app --no-restore /p:UseAppHost=false

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app
ENV ASPNETCORE_HTTP_PORTS=8080 \
    Storage__RootPath=/data/recordings
RUN mkdir -p /data/recordings && chown -R $APP_UID /data
COPY --from=build /app .
USER $APP_UID
EXPOSE 8080
VOLUME ["/data/recordings"]
ENTRYPOINT ["dotnet", "RecordingServer.dll"]
