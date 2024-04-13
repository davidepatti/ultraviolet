#!/bin/bash

CLASSPATH=.:json-simple-1.1.jar
javac -cp "$CLASSPATH" *.java

if [ $? -ne 0 ]; then
    echo "Compilation failed."
    exit 1
fi

echo "Compilation successful."
##java -cp "$CLASSPATH" UltraViolet

