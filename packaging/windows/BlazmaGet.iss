; Windows installer for Blazma Get (Inno Setup 6.5+).
;
; Wraps the self-contained app image made by packaging/build-bundle.ps1 -Type app-image
; (build\dist\BlazmaGet: BlazmaGet.exe plus a trimmed Java runtime), so users need nothing else.
; Installs per user by default (no admin prompt), with an Arabic or English wizard.
;
;   iscc /DAppVersion=1.0.0 packaging\windows\BlazmaGet.iss
;
; Output: build\installer\BlazmaGet-Setup-<version>.exe

#define AppName "Blazma Get"
#define AppExe "BlazmaGet.exe"
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\..\build\dist\BlazmaGet"
#endif

[Setup]
; Same GUID as the jpackage MSI upgrade code, so both describe one product.
AppId={{A12CA387-15D1-4C11-AF72-8A2856CED1DE}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Blazma
AppPublisherURL=https://blazma.online
AppSupportURL=https://github.com/mr-kateba/Blazma-Get/issues
AppUpdatesURL=https://github.com/mr-kateba/Blazma-Get/releases
VersionInfoVersion={#AppVersion}
VersionInfoDescription={#AppName} Setup
DefaultDirName={autopf}\BlazmaGet
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\..\build\installer
OutputBaseFilename=BlazmaGet-Setup-{#AppVersion}
SetupIconFile=..\icons\blazma-get.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
LicenseFile=..\..\LICENSE
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ShowLanguageDialog=auto
CloseApplications=yes

[Languages]
Name: "arabic"; MessagesFile: "Arabic.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; The app keeps running in the tray; stop it so its files can be removed.
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM {#AppExe}"; Flags: runhidden; RunOnceId: "StopBlazmaGet"

[Registry]
; The app adds this start-on-login value itself; nothing is written at install, it is only removed
; on uninstall so Windows does not try to start a program that is gone.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "BlazmaGet"; Flags: uninsdeletevalue dontcreatekey
