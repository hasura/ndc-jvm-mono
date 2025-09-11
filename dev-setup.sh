#!/bin/bash
# Exit immediately if a command exits with a non-zero status
set -e

# Initialize variables
WITH_INTROSPECTION=false
DB_ONLY=false
NO_RUN=false
CLEANUP=false
CONNECTOR=""

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --with-introspection)
            WITH_INTROSPECTION=true
            shift
            ;;
        --db-only)
            DB_ONLY=true
            shift
            ;;
        --no-run)
            NO_RUN=true
            shift
            ;;
        --cleanup)
            CLEANUP=true
            shift
            ;;
        -*)
            echo "Error: Unknown option $1"
            echo "Usage: $0 [--with-introspection] [--db-only] [--no-run] [--cleanup] <connector_name>"
            exit 1
            ;;
        *)
            if [ -z "$CONNECTOR" ]; then
                CONNECTOR=$1
            else
                echo "Error: Multiple connector names provided"
                echo "Usage: $0 [--with-introspection] [--db-only] [--no-run] [--cleanup] <connector_name>"
                exit 1
            fi
            shift
            ;;
    esac
done

# Function to cleanup MySQL database
cleanup_mysql_database() {
    echo "Cleaning up MySQL database..."

    # Stop and remove MySQL container
    if docker ps -a --format "table {{.Names}}" | grep -q "^mysql-chinook$"; then
        echo "Stopping and removing MySQL container 'mysql-chinook'..."
        docker stop mysql-chinook >/dev/null 2>&1 || true
        docker rm mysql-chinook >/dev/null 2>&1 || true
        echo "MySQL container cleanup complete!"
    else
        echo "MySQL container 'mysql-chinook' not found"
    fi
}

# Handle cleanup mode
if [ "$CLEANUP" = true ]; then
    if [ -n "$CONNECTOR" ] && [[ "$CONNECTOR" == "mysql" ]]; then
        cleanup_mysql_database
    elif [ -z "$CONNECTOR" ]; then
        echo "Cleaning up all known databases..."
        cleanup_mysql_database
    else
        echo "Cleanup not implemented for connector: $CONNECTOR"
    fi
    exit 0
fi

# Check if connector argument is provided
if [ -z "$CONNECTOR" ]; then
    echo "Error: Connector name is required"
    echo "Usage: $0 [--with-introspection] [--db-only] [--no-run] [--cleanup] <connector_name>"
    echo ""
    echo "Options:"
    echo "  --with-introspection  Run introspection after database setup"
    echo "  --db-only            Only setup database, don't run connector"
    echo "  --no-run             Setup database and run introspection but don't start connector"
    echo "  --cleanup            Cleanup database containers and exit"
    echo ""
    echo "Examples:"
    echo "  $0 mysql                           # Setup MySQL and run connector"
    echo "  $0 --with-introspection mysql      # Setup MySQL, run introspection, then run connector"
    echo "  $0 --db-only mysql                 # Only setup MySQL database"
    echo "  $0 --cleanup mysql                 # Cleanup MySQL database container"
    exit 1
fi

# Convert to uppercase for environment variable
CONNECTOR_UPPER=$(echo "$CONNECTOR" | tr '[:lower:]' '[:upper:]')
ENV_VAR_NAME="${CONNECTOR_UPPER}_URL"

# Function to setup MySQL database
setup_mysql_database() {
    echo "Setting up MySQL database..."

    # Check if Docker is running
    if ! docker info >/dev/null 2>&1; then
        echo "Error: Docker is not running. Please start Docker and try again."
        exit 1
    fi

    # Check if MySQL container is already running
    if docker ps --format "table {{.Names}}" | grep -q "^mysql-chinook$"; then
        echo "MySQL container 'mysql-chinook' is already running"
    else
        echo "Starting MySQL container..."

        # Stop and remove existing container if it exists
        docker stop mysql-chinook >/dev/null 2>&1 || true
        docker rm mysql-chinook >/dev/null 2>&1 || true

        # Start MySQL container
        docker run -d \
            --name mysql-chinook \
            -e MYSQL_ROOT_PASSWORD=Password123 \
            -p 3306:3306 \
            --health-cmd="mysqladmin ping -h localhost -u root -pPassword123" \
            --health-interval=5s \
            --health-timeout=10s \
            --health-retries=10 \
            --health-start-period=20s \
            mysql:8.4

        if [ $? -ne 0 ]; then
            echo "Error: Failed to start MySQL container"
            exit 1
        fi

        echo "Waiting for MySQL to be ready..."

        # Wait for MySQL to be ready (up to 2 minutes)
        timeout 120 bash -c '
            while true; do
                if docker exec mysql-chinook mysqladmin ping -u root -pPassword123 >/dev/null 2>&1; then
                    echo "MySQL is ready!"
                    break
                fi
                echo "Waiting for MySQL..."
                sleep 2
            done
        '

        if [ $? -ne 0 ]; then
            echo "Error: MySQL failed to start within 2 minutes"
            echo "Container logs:"
            docker logs mysql-chinook
            exit 1
        fi

        # Import Chinook database
        echo "Importing Chinook database..."
        if [ -f "ndc-spec-tests/mysql/chinook.sql" ]; then
            docker exec -i mysql-chinook mysql -u root -pPassword123 < ndc-spec-tests/mysql/chinook.sql
            if [ $? -eq 0 ]; then
                echo "Chinook database imported successfully"

                # Verify database was created
                if docker exec mysql-chinook mysql -u root -pPassword123 -e "SHOW DATABASES;" | grep -q Chinook; then
                    echo "Chinook database verified"
                else
                    echo "Warning: Chinook database not found after import"
                fi
            else
                echo "Error: Failed to import Chinook database"
                exit 1
            fi
        else
            echo "Error: Chinook SQL file not found at ndc-spec-tests/mysql/chinook.sql"
            exit 1
        fi
    fi

    # Set JDBC_URL for MySQL
    export JDBC_URL="jdbc:mysql://localhost:3306/Chinook?user=root&password=Password123"
    export "$ENV_VAR_NAME"="$JDBC_URL"
    echo "MySQL setup complete!"
    echo "JDBC_URL: $JDBC_URL"
    echo "$ENV_VAR_NAME exported: ${!ENV_VAR_NAME}"
}

echo "Setting up $CONNECTOR connector..."

# Check if JDBC_URL is already set
if [ -n "$JDBC_URL" ]; then
    echo "JDBC_URL environment variable found: $JDBC_URL"
    echo "Using existing JDBC_URL, skipping database setup..."
    export "$ENV_VAR_NAME"="$JDBC_URL"
    echo "$ENV_VAR_NAME exported: ${!ENV_VAR_NAME}"
else
    echo "JDBC_URL not found, proceeding with database setup..."

    # Setup MySQL database if connector is mysql
    if [[ "$CONNECTOR" == "mysql" ]]; then
        setup_mysql_database
    else
        echo "Error: Database setup not implemented for connector: $CONNECTOR"
        echo "Please set JDBC_URL environment variable or implement setup for this connector"
        exit 1
    fi
fi

# If --db-only flag is set, only start the database and exit
if [ "$DB_ONLY" = true ]; then
    echo "Database setup complete! (--db-only flag used)"
    echo "Database is running and ready for connections"
    if [[ "$CONNECTOR" == "mysql" ]] || [ -n "$JDBC_URL" ]; then
        echo "Connection URL: ${!ENV_VAR_NAME}"
    fi
    exit 0
fi

# Run introspection only if flag is set
if [ "$WITH_INTROSPECTION" = true ]; then
    echo "Introspecting the $CONNECTOR ..."
    mkdir -p "configs/$CONNECTOR"
    make "run-$CONNECTOR-cli-introspection" || { echo "make run-$CONNECTOR-cli-introspection failed"; exit 1; }
else
    echo "Skipping introspection (use --with-introspection flag to enable)"
fi

# Run connector only if --no-run flag is not set
if [ "$NO_RUN" = true ]; then
    echo "Skipping connector execution (--no-run flag used)"
    echo "$CONNECTOR setup complete! (connector not started)"
else
    echo "Running make run-$CONNECTOR-connector from root directory..."
    make "run-$CONNECTOR"-connector || { echo "make run-$CONNECTOR-connector failed"; exit 1; }
    echo "$CONNECTOR setup complete!"
fi