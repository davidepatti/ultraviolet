#!/bin/bash

if [ $# -ne 3 ]; then
    echo "Usage: $0 <string S> <N> <filename>"
    exit 1
fi

S="$1"
N="$2"
FILE="$3"

# Prepare the list of all pubkeys in the form pk<n>
ALL_PK=()
for i in $(seq 0 $N); do
    ALL_PK+=("pk$i")
done

# Find all pk<n> mentioned on lines containing S
USED_PK=()
while read -r line; do
    if [[ "$line" == *"$S"* ]]; then
        for i in $(seq 0 $N); do
            if [[ "$line" =~ pk$i ]]; then
                USED_PK+=("pk$i")
            fi
        done
    fi
done < "$FILE"

# Remove duplicates from USED_PK
USED_PK=($(printf "%s\n" "${USED_PK[@]}" | sort -u))

# Print pk<n> not mentioned
for pk in "${ALL_PK[@]}"; do
    found=0
    for used in "${USED_PK[@]}"; do
        if [ "$pk" == "$used" ]; then
            found=1
            break
        fi
    done
    if [ $found -eq 0 ]; then
        echo "$pk"
    fi
done
