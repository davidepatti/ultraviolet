#!/bin/bash
      CLASSPATH=.:json-simple-1.1.jar
      javac -cp "$CLASSPATH" *.java
      if [ $? -ne 0 ]; then
          echo "Compilation failed."
          exit 1
      fi
      echo "Compilation successful."

      # Create a jar file
      jar cfe UltraViolet.jar UltraViolet *.class

      # Note:
      # c: create new archive
      # f: name of the archive file
      # e: application entry point for standalone application bundled into an executable jar file
      #
      # UltraViolet.jar: the name of the jar file you want to create
      # UltraViolet: the name of the 'main' class you want to start when the jar is run
      #
      # *.class: this includes all compiled sources (class files)
      #
      # Ensure the main class is correctly pointed in manifest inside jar.
      # The manifest should have a line like this: "Main-Class: path.to.your.UltraVioletClass"

      echo "Jar file created."

      rm *.class
      mv UltraViolet.jar ..
      echo "Please return to the main directory and execute the jar file"

