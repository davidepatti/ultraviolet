#!/bin/bash
      CLASSPATH=.:json-simple-1.1.jar
      javac -cp "$CLASSPATH" *.java
      if [ $? -ne 0 ]; then
          echo "Compilation failed."
          exit 1
      fi
      echo "Compilation successful."

      # Create a jar file
      jar cfe UltraViolet.jar UltraViolet -C . .

      echo "Jar file created."

      find . -name "*.class" -delete
      mv UltraViolet.jar ..
      echo "**** Please return to the main directory and execute the jar file ***"

