// Arquivo de configuracao do Gradle (o sistema que compila o projeto Android).
// Aqui a gente so declara onde baixar as bibliotecas (repositorios) e quais
// modulos fazem parte do projeto (no nosso caso, so o modulo "app").

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AplicativoK"
include(":app")
