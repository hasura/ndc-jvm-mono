#!/usr/bin/env python3
"""
Schema Validator for NDC Connectors

This script validates that an expected schema (subset) is present in the actual schema
returned by a connector. The actual schema can contain additional fields, but all
expected fields must be present with matching values and structure.

For arrays, the order of elements is ignored - only the presence of matching elements matters.

Usage:
    python3 schema-validator.py <expected_schema.json> <actual_schema.json>

Exit codes:
    0: Validation passed
    1: Validation failed or error occurred
"""

import json
import sys
from typing import Any, Union


def is_subset(expected: Any, actual: Any, path: str = "") -> bool:
    """
    Check if expected is a subset of actual.

    Args:
        expected: The expected data structure (subset)
        actual: The actual data structure (can contain extra fields)
        path: Current path in the data structure for error reporting

    Returns:
        True if all keys/values in expected exist in actual with same structure
    """
    if isinstance(expected, dict) and isinstance(actual, dict):
        for key, expected_value in expected.items():
            current_path = f"{path}.{key}" if path else key

            if key not in actual:
                print(f"❌ Missing key: {current_path}")
                return False

            if not is_subset(expected_value, actual[key], current_path):
                return False
        return True

    elif isinstance(expected, list) and isinstance(actual, list):
        if len(expected) > len(actual):
            print(f"❌ Expected list longer than actual at {path} (expected: {len(expected)}, actual: {len(actual)})")
            return False

        # For each expected item, find a matching item in the actual array
        actual_items_used = set()

        for i, expected_item in enumerate(expected):
            current_path = f"{path}[{i}]"
            found_match = False

            # Try to find a matching item in the actual array that hasn't been used yet
            for j, actual_item in enumerate(actual):
                if j in actual_items_used:
                    continue

                # Check if this actual item matches the expected item
                if is_items_equal(expected_item, actual_item):
                    actual_items_used.add(j)
                    found_match = True
                    break

            if not found_match:
                print(f"❌ No matching item found for expected element at {current_path}")
                return False

        return True

    else:
        # For primitive values (string, number, boolean, null)
        if expected != actual:
            print(f"❌ Value mismatch at {path}: expected {expected} ({type(expected).__name__}), got {actual} ({type(actual).__name__})")
            return False
        return True


def is_items_equal(expected: Any, actual: Any) -> bool:
    """
    Check if two items are equal, handling nested structures.
    This is used for comparing array elements without caring about their position.

    Args:
        expected: Expected item
        actual: Actual item

    Returns:
        True if items are structurally equal
    """
    if isinstance(expected, dict) and isinstance(actual, dict):
        # For objects, all keys in expected must exist in actual with matching values
        for key, expected_value in expected.items():
            if key not in actual:
                return False
            if not is_items_equal(expected_value, actual[key]):
                return False
        return True

    elif isinstance(expected, list) and isinstance(actual, list):
        # For arrays, recursively check subset relationship (order-agnostic)
        if len(expected) > len(actual):
            return False

        actual_items_used = set()
        for expected_item in expected:
            found_match = False
            for j, actual_item in enumerate(actual):
                if j in actual_items_used:
                    continue
                if is_items_equal(expected_item, actual_item):
                    actual_items_used.add(j)
                    found_match = True
                    break
            if not found_match:
                return False
        return True

    else:
        # For primitive values
        return expected == actual


def load_json_file(filepath: str) -> Union[dict, list]:
    """
    Load and parse a JSON file.

    Args:
        filepath: Path to the JSON file

    Returns:
        Parsed JSON data

    Raises:
        FileNotFoundError: If file doesn't exist
        json.JSONDecodeError: If file contains invalid JSON
    """
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            return json.load(f)
    except FileNotFoundError:
        raise FileNotFoundError(f"File not found: {filepath}")
    except json.JSONDecodeError as e:
        raise json.JSONDecodeError(f"Invalid JSON in {filepath}: {e.msg}", e.doc, e.pos)


def validate_schemas(expected_file: str, actual_file: str) -> bool:
    """
    Validate that expected schema is a subset of actual schema.

    Args:
        expected_file: Path to expected schema JSON file
        actual_file: Path to actual schema JSON file

    Returns:
        True if validation passes, False otherwise
    """
    try:
        print(f"🔍 Loading expected schema from: {expected_file}")
        expected_schema = load_json_file(expected_file)

        print(f"🔍 Loading actual schema from: {actual_file}")
        actual_schema = load_json_file(actual_file)

        print("🔍 Validating schema compatibility (order-agnostic for arrays)...")
        print()

        if is_subset(expected_schema, actual_schema):
            print("✅ Schema validation passed: All expected elements found in actual schema")
            return True
        else:
            print()
            print("❌ Schema validation failed: Missing or mismatched elements")
            print()
            print("The expected schema should be a subset of the actual schema.")
            print("This means the actual schema can have extra fields, but all expected fields must be present.")
            print("Array element order is ignored during comparison.")
            return False

    except Exception as e:
        print(f"❌ Error during validation: {e}")
        return False


def main():
    """Main entry point for the schema validator."""
    if len(sys.argv) != 3:
        print("Usage: python3 schema-validator.py <expected_schema.json> <actual_schema.json>")
        print()
        print("This script validates that the expected schema (subset) is present in the actual schema.")
        print("The actual schema can contain additional fields not in the expected schema.")
        print("Array element order is ignored during comparison.")
        sys.exit(1)

    expected_file = sys.argv[1]
    actual_file = sys.argv[2]

    print("🚀 NDC Schema Validator (Order-Agnostic)")
    print("=" * 50)

    success = validate_schemas(expected_file, actual_file)

    if success:
        sys.exit(0)
    else:
        sys.exit(1)


if __name__ == "__main__":
    main()