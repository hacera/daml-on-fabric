.PHONY: compile format-check test package it

compile:
	sbt compile

cleanCompile:
	sbt clean cleanFiles
	sbt compile

format-check: compile
	sbt scalafmtCheckAll

format-all:
	sbt scalafmtAll

test: compile
	sbt test

package: compile
	sbt assembly

it: package
	echo "skipping IT until script is working again"
	#bash ./it.sh
