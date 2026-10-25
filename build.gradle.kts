plugins {
    java
}

// 设置 Java 8 兼容性
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// 设置编译编码为 UTF-8
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

/*
Setup environment variables
* stsInstallLocation should point to the Steam install directory
* compileOnlyLibs should point to a directory containing any JARs you reference
    - e.g. this directory should have desktop-1.0.jar, ModTheSpire.jar, BaseMod.jar
    - NOTE: these compileOnlyLibs are not included in the JAR, so you will get runtime
      errors if you try and call code that won't exist on the client.
 */
// Steam / 游戏实际安装位置（本机在 C 盘，原配置指向不存在的 D 盘）
// 若你的游戏装在其它位置，改这里即可；也支持用环境变量覆盖：
//   STS_INSTALL  -> 游戏安装目录（SlayTheSpire）
//   STEAMAPPS    -> Steam 的 steamapps 目录
var steamappsLocation: String = System.getenv("STEAMAPPS") ?: "C:/Program Files (x86)/Steam/steamapps"
var stsInstallLocation: String = System.getenv("STS_INSTALL") ?: "C:/Program Files (x86)/Steam/steamapps/common/SlayTheSpire"

var workshopLocation: String = "$steamappsLocation/workshop/content/646570"

var modTheSpireLocation: String = "$workshopLocation/1605060445/ModTheSpire.jar"
var baseModLocation: String = "$workshopLocation/1605833019/BaseMod.jar"
var runHistoryPlusLocation: String = "$workshopLocation/2802958032/RunHistoryPlus.jar"
var stsJar: String = "$stsInstallLocation/desktop-1.0.jar"


// Uses the value written in settings.gradle
var modName: String = rootProject.name

// 自定义版本号，每次测试时修改此处
var versionTag: String = "V4.5.3"

// JAR 文件命名：使用 ModTheSpire.json 中的 modid
var jarBaseName: String = "TinyCat"

repositories {
    mavenCentral()
}

dependencies {
    // 只保留实际存在的依赖，缺失的 jar 会被自动跳过
    val localDeps = listOf(modTheSpireLocation, baseModLocation, runHistoryPlusLocation, stsJar)
        .filter { File(it).exists() }
    compileOnly(files(localDeps))
    
    // 使用游戏自带的 Gson，不需要额外依赖
}

// 配置资源文件，将 location 目录加入 resources
sourceSets {
    main {
        resources {
            srcDirs("src/main/resources", "location")
        }
    }
    // 独立测试模组：记录金币/HP，不打包进 TinyCat 正式版
    create("trackerTest") {
        java.srcDir("tracker-test/src/main/java")
        resources.srcDir("tracker-test/src/main/resources")
        compileClasspath += sourceSets.main.get().compileClasspath
    }
}

// 设置资源文件的重复处理策略
tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}


tasks.register("srcsets") {
    doLast {
        sourceSets.forEach { srcSet ->
            println(srcSet.name)
            println(srcSet.allJava.srcDirs)
        }
    }
}

// --------------------------------------------------------------------------------

tasks.register<Jar>("buildJAR") {
    group = "Slay the Spire"
    description = "Builds a fat (includes runtime dependencies) JAR in the build/libs folder"

    // 设置输出 JAR 文件名：TinyCat_V4.2.2.jar
    archiveFileName.set("${jarBaseName}_${versionTag}.jar")

    // Main code
    from(sourceSets.main.get().output)

    // Any runtime dependencies (e.g. from mavenCentral(), local JARs, etc.)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter {
            it.name.endsWith("jar")
        }.map {
            zipTree(it)
        }
    })
}

// 内部测试用：把本地怪物图包打进 jar（tinycat/monsters/*.png）
// 图来自 mods/TinyCat/monster-images，不进仓库、不对外分发（CC BY-NC-SA，见 docs/STATE-NOTES-AND-GOLD.md）
tasks.register<Jar>("buildJARWithImages") {
    group = "Slay the Spire"
    description = "内部测试用：把本地怪物图包打进 jar，生成 ${jarBaseName}_${versionTag}_images.jar"

    archiveFileName.set("${jarBaseName}_${versionTag}_images.jar")

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter {
            it.name.endsWith("jar")
        }.map {
            zipTree(it)
        }
    })

    from(fileTree("$stsInstallLocation/mods/TinyCat/monster-images") { include("*.png") }) {
        into("tinycat/monsters")
    }
}

tasks.register<Copy>("buildAndCopyJARWithImages") {
    group = "Slay the Spire"
    description = "把带图的内部测试 jar 复制到 \$STS_INSTALL/mods（会与普通版同名 modid 冲突，留意只保留一个）"

    dependsOn("buildJARWithImages")
    from("build/libs/${jarBaseName}_${versionTag}_images.jar")
    into("$stsInstallLocation/mods")
}

tasks.register<Jar>("buildTrackerTestJAR") {    group = "Slay the Spire"
    description = "Builds the optional TinyCat tracker test mod JAR"

    archiveFileName.set("TinyCatTrackerTest_V0.2.0.jar")
    from(sourceSets["trackerTest"].output)
}

tasks.register<Copy>("buildAndCopyJAR") {
    group = "Slay the Spire"
    description = "Copies the JAR from your build/libs folder into your \$STS_INSTALL location"

    dependsOn("buildJAR")

    from("build/libs/${jarBaseName}_${versionTag}.jar")
    into("$stsInstallLocation/mods")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
