plugins {
    id("java")
}

group = "org.springframework"
version = "6.1.2-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
	implementation(project(":spring-context"))
	implementation(project(":spring-core"))
	implementation(project(":spring-instrument"))
}

tasks.test {
    useJUnitPlatform()
}