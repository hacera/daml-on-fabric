.PHONY: compile format-check test package it

compile:
	mvn compile assembly:single
	sbt compile

cleanCompile:
	mvn clean compile assembly:single
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
	bash ./it.sh
