FROM mcr.microsoft.com/dotnet/sdk:9.0 AS builder

WORKDIR /app

# Copy project file and restore dependencies
COPY *.csproj .
RUN dotnet restore

# Copy source code and build
COPY . .
RUN dotnet publish -c Release -o out

# Runtime stage
FROM mcr.microsoft.com/dotnet/aspnet:9.0

WORKDIR /app

# Copy built application
COPY --from=builder /app/out .

# Note: ASP.NET images already include a non-root user 'app'
# No need to create the user, just switch to it
USER app

EXPOSE 8000

ENV ASPNETCORE_URLS=http://+:8000

CMD ["dotnet", "dotnet.dll"]