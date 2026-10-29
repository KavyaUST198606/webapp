import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

version = "2025.03"

// Reusable template: all steps, triggers, requirements, and artifact rules live here.
object DotNetFrameworkWebAppVS2019 : Template({
    id("DotNetFrameworkWebAppVS2019")
    name = "DotNet Framework Web App - VS2019"
    description = "Restore, build, test, SonarQube, Snyk SAST/SCA, GitLeaks, package, and publish for .NET Framework web apps."

    params {
        text("repo.url", "", label = "Repository URL", description = "Git repository URL", allowEmpty = false)
        text("branch.name", "refs/heads/main", label = "Branch", description = "Branch to build", allowEmpty = false)
        text("solution.path", "", label = "Solution path", description = "Solution file relative to checkout, e.g. src\\MyWebApp.sln", allowEmpty = false)
        text("application.id", "", label = "Application ID", description = "Sonar project key and package name", allowEmpty = false)
        text("custom.owner.team", "", label = "Owning team", description = "Required custom property", allowEmpty = false)
        text("msbuild.configuration", "Release", label = "MSBuild configuration", allowEmpty = false)
        text("msbuild.platform", "Any CPU", label = "MSBuild platform", allowEmpty = false)
        text("nuget.path", "nuget.exe", label = "NuGet CLI path", allowEmpty = false)
        text("vstest.path", """C:\Program Files (x86)\Microsoft Visual Studio\2019\Enterprise\Common7\IDE\CommonExtensions\Microsoft\TestWindow\vstest.console.exe""", label = "VSTest console path", allowEmpty = false)
        text("test.dll.pattern", "*Tests.dll", label = "Test assembly pattern", allowEmpty = false)
        text("artifact.dir", "artifacts", label = "Artifact folder", allowEmpty = false)
        text("package.source", "src\\SimpleWebApp", label = "Web project folder to package", allowEmpty = false)
        text("deploy.package.name", "%application.id%-%build.number%.zip", label = "Package file name", allowEmpty = false)
        text("sonar.host.url", "", label = "SonarQube URL", allowEmpty = false)
        password("sonar.token", "", label = "SonarQube token")
        password("snyk.token", "", label = "Snyk token")
        checkbox("enable.sonar", "true", label = "Run SonarQube", checked = "true", unchecked = "false")
        checkbox("enable.snyk", "true", label = "Run Snyk", checked = "true", unchecked = "false")
        checkbox("enable.gitleaks", "true", label = "Run GitLeaks", checked = "true", unchecked = "false")
    }

    steps {
        // 1. Validate repository metadata and required custom properties
        script {
            name = "Validate metadata and required properties"
            scriptContent = """
                @echo off
                if "%repo.url%"=="" (echo ##teamcity[buildProblem description='Missing required parameter: repo.url'] & exit /b 1)
                if "%branch.name%"=="" (echo ##teamcity[buildProblem description='Missing required parameter: branch.name'] & exit /b 1)
                if "%application.id%"=="" (echo ##teamcity[buildProblem description='Missing required parameter: application.id'] & exit /b 1)
                if "%custom.owner.team%"=="" (echo ##teamcity[buildProblem description='Missing required custom property: custom.owner.team'] & exit /b 1)
                if not exist "%solution.path%" (echo ##teamcity[buildProblem description='Solution not found: %solution.path%'] & exit /b 1)
                echo Metadata validated
            """.trimIndent()
        }

        // 2. Restore NuGet packages
        script {
            name = "Restore NuGet packages"
            scriptContent = """"%nuget.path%" restore "%solution.path%""""
        }

        // 3. SonarQube begin
        script {
            name = "SonarQube analysis - begin"
            scriptContent = """
                if "%enable.sonar%"=="true" SonarScanner.MSBuild.exe begin /k:%application.id% /d:sonar.host.url=%sonar.host.url% /d:sonar.token=%sonar.token%
            """.trimIndent()
        }

        // 4. Build with MSBuild
        script {
            name = "Build solution (MSBuild)"
            scriptContent = """
                msbuild "%solution.path%" /t:Rebuild /p:Configuration=%msbuild.configuration% /p:Platform="%msbuild.platform%" /p:DeployOnBuild=false /nologo /m
            """.trimIndent()
        }

        // 5. Unit tests and code coverage
        script {
            name = "Unit tests and code coverage"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = """
                powershell -NoProfile -ExecutionPolicy Bypass -Command "${'$'}dlls = Get-ChildItem -Recurse -Filter '%test.dll.pattern%' | Where-Object { ${'$'}_.FullName -like '*\bin\%msbuild.configuration%\*' } | ForEach-Object { ${'$'}_.FullName }; if (-not ${'$'}dlls) { Write-Host '##teamcity[buildProblem description=''No test assemblies found'']'; exit 1 }; New-Item -ItemType Directory -Force -Path '%artifact.dir%\TestResults' | Out-Null; & '%vstest.path%' ${'$'}dlls /Logger:trx /EnableCodeCoverage /ResultsDirectory:'%artifact.dir%\TestResults'; exit ${'$'}LASTEXITCODE"
            """.trimIndent()
        }

        // 6. SonarQube end and quality gate
        script {
            name = "SonarQube analysis - end and quality gate"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = """
                if "%enable.sonar%"=="true" SonarScanner.MSBuild.exe end /d:sonar.token=%sonar.token%
            """.trimIndent()
        }

        // 7. Snyk SAST and dependency scans
        script {
            name = "Snyk SAST and dependency scans"
            scriptContent = """
                if "%enable.snyk%"=="true" (
                snyk auth %snyk.token%
                snyk test --all-projects --severity-threshold=high
                snyk code test --severity-threshold=high
                )
            """.trimIndent()
        }

        // 8. GitLeaks secret scanning
        script {
            name = "GitLeaks secret scanning"
            scriptContent = """
                if "%enable.gitleaks%"=="true" (
                if not exist "%artifact.dir%" mkdir "%artifact.dir%"
                gitleaks detect --source=. --redact --report-format=json --report-path=%artifact.dir%\gitleaks-report.json --exit-code=1
                )
            """.trimIndent()
        }

        // 9. Package build outputs
        script {
            name = "Package build outputs"
            scriptContent = """
                powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '%artifact.dir%' | Out-Null; Compress-Archive -Path '%package.source%\*' -DestinationPath '%artifact.dir%\%deploy.package.name%' -Force"
            """.trimIndent()
        }
    }

    triggers {
        vcs {
            branchFilter = "+:%branch.name%"
        }
    }

    failureConditions {
        nonZeroExitCode = true
    }

    requirements {
        exists("env.VS160COMNTOOLS")
        equals("teamcity.agent.jvm.os.name", "Windows")
    }

    artifactRules = """
        %artifact.dir%/TestResults/**/*.trx => reports
        %artifact.dir%/TestResults/**/*.coverage => coverage
        %artifact.dir%/gitleaks-report.json => reports
        %artifact.dir%/%deploy.package.name% => packages
    """.trimIndent()
})

// Source repo that holds MyDotNetFrameworkApp.sln, the SimpleWebApp project and its tests.
object DotNetWebAppGitRoot : GitVcsRoot({
    id("DotNetWebAppGitRoot")
    name = "Dotnet Framework Web App"
    url = "https://github.com/KavyaUST198606/webapp.git"
    branch = "refs/heads/main"
    branchSpec = "+:refs/heads/*"
})

// Sample 1: full pipeline on main, Release, all scans on.
object DotNetWebAppReleaseBuild : BuildType({
    id("DotNetWebAppReleaseBuild")
    name = "Dotnet Framework Web App - Release (Full)"

    templates(DotNetFrameworkWebAppVS2019)

    vcs {
        root(DotNetWebAppGitRoot)
    }

    params {
        param("repo.url", "https://github.com/KavyaUST198606/webapp.git")
        param("branch.name", "refs/heads/main")
        param("solution.path", "MyDotNetFrameworkApp.sln")
        param("application.id", "MyDotNetFrameworkApp")
        param("custom.owner.team", "<owning-team>")
        param("sonar.host.url", "https://<sonarqube-host>")
        param("msbuild.configuration", "Release")
    }
})

// Sample 2: quick Debug build on feature branches, security scans off.
object DotNetWebAppDebugBuild : BuildType({
    id("DotNetWebAppDebugBuild")
    name = "Dotnet Framework Web App - Debug (Quick)"

    templates(DotNetFrameworkWebAppVS2019)

    vcs {
        root(DotNetWebAppGitRoot)
    }

    params {
        param("repo.url", "https://github.com/KavyaUST198606/webapp.git")
        param("branch.name", "refs/heads/feature/*")
        param("solution.path", "MyDotNetFrameworkApp.sln")
        param("application.id", "MyDotNetFrameworkApp")
        param("custom.owner.team", "<owning-team>")
        param("sonar.host.url", "https://<sonarqube-host>")
        param("msbuild.configuration", "Debug")
        param("enable.sonar", "false")
        param("enable.snyk", "false")
        param("enable.gitleaks", "false")
    }
})

// Sample 3: main branch with SonarQube off; Snyk and GitLeaks still run.
object DotNetWebAppSecurityScan : BuildType({
    id("DotNetWebAppSecurityScan")
    name = "Dotnet Framework Web App - Security Scan"

    templates(DotNetFrameworkWebAppVS2019)

    vcs {
        root(DotNetWebAppGitRoot)
    }

    params {
        param("repo.url", "https://github.com/KavyaUST198606/webapp.git")
        param("branch.name", "refs/heads/main")
        param("solution.path", "MyDotNetFrameworkApp.sln")
        param("application.id", "MyDotNetFrameworkApp")
        param("custom.owner.team", "<owning-team>")
        param("sonar.host.url", "https://<sonarqube-host>")
        param("enable.sonar", "false")
    }
})

project {
    vcsRoot(DotNetWebAppGitRoot)
    template(DotNetFrameworkWebAppVS2019)
    buildType(DotNetWebAppReleaseBuild)
    buildType(DotNetWebAppDebugBuild)
    buildType(DotNetWebAppSecurityScan)
}
