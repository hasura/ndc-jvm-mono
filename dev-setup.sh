#!/bin/bash
# Exit immediately if a command exits with a non-zero status
set -e

# Initialize variables
WITH_INTROSPECTION=false
DB_ONLY=false
NO_RUN=false
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
        -*)
            echo "Error: Unknown option $1"
            echo "Usage: $0 [--with-introspection] [--db-only] [--no-run] <connector_name>"
            exit 1
            ;;
        *)
            if [ -z "$CONNECTOR" ]; then
                CONNECTOR=$1
            else
                echo "Error: Multiple connector names provided"
                echo "Usage: $0 [--with-introspection] [--db-only] [--no-run] <connector_name>"
                exit 1
            fi
            shift
            ;;
    esac
done

# Check if connector argument is provided
if [ -z "$CONNECTOR" ]; then
    echo "Error: Connector name is required"
    echo "Usage: $0 [--with-introspection] [--db-only] [--no-run] <connector_name>"
    exit 1
fi

# Convert to uppercase for environment variable
CONNECTOR_UPPER=$(echo "$CONNECTOR" | tr '[:lower:]' '[:upper:]')
ENV_VAR_NAME="${CONNECTOR_UPPER}_URL"

echo "Setting up $CONNECTOR connector..."

# Check if JDBC_URL is already set
if [ -n "$JDBC_URL" ]; then
    echo "JDBC_URL environment variable found: $JDBC_URL"
    echo "Using existing JDBC_URL, skipping database setup..."
    export "$ENV_VAR_NAME"="$JDBC_URL"
    echo "$ENV_VAR_NAME exported: ${!ENV_VAR_NAME}"
else
    echo "JDBC_URL not found, proceeding with database setup..."

    # Store original directory
    ORIGINAL_DIR=$(pwd)

    # Check if connector requires local Docker setup
    if [[ "$CONNECTOR" == "mysql" ]]; then
        # Navigate to connector setup directory
        SETUP_DIR="dev/$CONNECTOR"
        echo "Changing to directory: $SETUP_DIR"
        cd "$SETUP_DIR" || { echo "Failed to change directory to $SETUP_DIR"; exit 1; }

        # Run docker compose
        echo "Starting $CONNECTOR with docker-compose..."
        docker compose up -d || { echo "Docker compose failed for $CONNECTOR"; exit 1; }

        # Wait for service to be ready
        echo "Waiting for $CONNECTOR to be ready..."
        sleep 5

        # Return to the original directory
        cd "$ORIGINAL_DIR" || { echo "Failed to return to root directory"; exit 1; }
    else
        echo "Using hosted database for $CONNECTOR connector (no Docker setup required)"
    fi

    # Set the environment variable based on connector type
    if [ "$CONNECTOR" = "mysql" ]; then
        export "$ENV_VAR_NAME"='jdbc:mysql://root:rootpassword@localhost:3306/Chinook?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
        echo "$ENV_VAR_NAME exported: ${!ENV_VAR_NAME}"
    else
        echo "Note: For hosted $CONNECTOR database, ensure $ENV_VAR_NAME is set in your environment"
        echo "You may need to set this manually or source a configuration file"
    fi

    export JDBC_URL=${!ENV_VAR_NAME}
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
    echo "Running make run-$CONNECTOR from root directory..."
    make "run-$CONNECTOR" || { echo "make run-$CONNECTOR failed"; exit 1; }
    echo "$CONNECTOR setup complete!"
fi