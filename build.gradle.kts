plugins {
    java
}

group = "com.itproblems.dbeaver"
version = "1.0.0"

val bundleSymbolicName = "com.itproblems.dbeaver.aurora.iam"

// The plugin compiles against the jars of a local DBeaver install, so no DBeaver artifacts need publishing anywhere.
// Override with -PdbeaverHome=/path/to/dbeaver (the directory that contains "plugins/").
val dbeaverHome: String = providers.gradleProperty("dbeaverHome")
    .getOrElse("/Applications/DBeaver.app/Contents/Eclipse")
val dbeaverPlugins = file("$dbeaverHome/plugins")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

configurations.all {
    // We only need one HTTP client (for SSO / STS credential calls); drop the heavy ones.
    exclude(group = "software.amazon.awssdk", module = "apache-client")
    exclude(group = "software.amazon.awssdk", module = "apache5-client")
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
}

dependencies {
    compileOnly(fileTree(dbeaverPlugins) {
        include(
            "org.jkiss.utils_*.jar",
            "org.jkiss.dbeaver.model_*.jar",
            "org.jkiss.dbeaver.ui_*.jar",
            "org.jkiss.dbeaver.ui.editors.connection_*.jar",
            "org.eclipse.swt*.jar",
            "org.eclipse.jface_*.jar",
            "org.eclipse.equinox.common_*.jar",
        )
    })

    implementation(platform("software.amazon.awssdk:bom:2.55.6"))
    implementation("software.amazon.awssdk:rds")
    implementation("software.amazon.awssdk:url-connection-client")
    // Loaded reflectively by ProfileCredentialsProvider for SSO and assume-role profiles.
    runtimeOnly("software.amazon.awssdk:sso")
    runtimeOnly("software.amazon.awssdk:ssooidc")
    runtimeOnly("software.amazon.awssdk:sts")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// OSGi bundle: our classes at the root, third-party jars embedded under lib/ and listed on Bundle-ClassPath.
tasks.jar {
    val embedded = configurations.runtimeClasspath
    archiveFileName = "$bundleSymbolicName-${project.version}.jar"
    from("plugin.xml")
    into("lib") { from(embedded) }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Bundle-ManifestVersion" to "2",
            "Bundle-Name" to "Aurora IAM Authentication",
            "Bundle-SymbolicName" to "$bundleSymbolicName;singleton:=true",
            "Bundle-Version" to project.version,
            "Bundle-Vendor" to "it-problems",
            "Bundle-ActivationPolicy" to "lazy",
            "Require-Capability" to "osgi.ee;filter:=\"(&(osgi.ee=JavaSE)(version=21))\"",
            "Require-Bundle" to listOf(
                "org.eclipse.core.runtime",
                "org.eclipse.swt",
                "org.eclipse.jface",
                "org.jkiss.utils",
                "org.jkiss.dbeaver.model",
                "org.jkiss.dbeaver.registry",
                "org.jkiss.dbeaver.ui",
                "org.jkiss.dbeaver.ui.editors.connection",
            ).joinToString(","),
            "Automatic-Module-Name" to bundleSymbolicName,
        )
    }
    doFirst {
        manifest.attributes(
            "Bundle-ClassPath" to (listOf(".") + embedded.get().files.map { "lib/${it.name}" }).joinToString(",")
        )
    }
}

// DBeaver ships the p2 dropins reconciler disabled, so register the bundle directly with the
// simple configurator: copy it into plugins/ and add (or replace) its line in bundles.info.
// Restart DBeaver afterwards. A DBeaver upgrade replaces the install, so re-run this after upgrading.
tasks.register("installPlugin") {
    dependsOn(tasks.jar)
    val bundleJar = tasks.jar.flatMap { it.archiveFile }
    val pluginsDir = file("$dbeaverHome/plugins")
    val bundlesInfo = file("$dbeaverHome/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info")
    val bundleVersion = project.version.toString()
    doLast {
        val jar = bundleJar.get().asFile
        pluginsDir.listFiles { f -> f.name.startsWith("$bundleSymbolicName-") && f.name.endsWith(".jar") }
            ?.forEach { it.delete() }
        jar.copyTo(pluginsDir.resolve(jar.name), overwrite = true)

        val backup = bundlesInfo.resolveSibling("bundles.info.bak")
        if (!backup.exists()) {
            bundlesInfo.copyTo(backup)
        }
        val lines = bundlesInfo.readLines().filterNot { it.startsWith("$bundleSymbolicName,") }
        bundlesInfo.writeText(
            (lines + "$bundleSymbolicName,$bundleVersion,plugins/${jar.name},4,false").joinToString("\n", postfix = "\n")
        )
        println("Installed ${jar.name} into $pluginsDir and registered it in $bundlesInfo")
    }
}

// Reverses installPlugin.
tasks.register("uninstallPlugin") {
    val pluginsDir = file("$dbeaverHome/plugins")
    val bundlesInfo = file("$dbeaverHome/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info")
    doLast {
        pluginsDir.listFiles { f -> f.name.startsWith("$bundleSymbolicName-") && f.name.endsWith(".jar") }
            ?.forEach { it.delete() }
        val lines = bundlesInfo.readLines().filterNot { it.startsWith("$bundleSymbolicName,") }
        bundlesInfo.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
