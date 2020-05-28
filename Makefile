compile:
	./gradlew compileGroovy

test: 
	./gradlew test

deploy:
	./gradlew -b deploy.gradle uploadArchives

release:
	./gradlew -b deploy.gradle closeAndReleaseRepository

