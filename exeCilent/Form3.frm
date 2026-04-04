VERSION 5.00
Begin VB.Form Form3 
   BorderStyle     =   1  'Fixed Single
   Caption         =   "Info"
   ClientHeight    =   3975
   ClientLeft      =   45
   ClientTop       =   390
   ClientWidth     =   4095
   Icon            =   "Form3.frx":0000
   LinkTopic       =   "Form3"
   MaxButton       =   0   'False
   MinButton       =   0   'False
   ScaleHeight     =   3975
   ScaleWidth      =   4095
   StartUpPosition =   2  '屏幕中心
   Begin VB.Label Label2 
      Height          =   375
      Left            =   1080
      TabIndex        =   1
      Top             =   2040
      Width           =   2295
   End
   Begin VB.Label Label1 
      Caption         =   "b26.4.4 - EXE"
      Height          =   255
      Left            =   1440
      TabIndex        =   0
      Top             =   1680
      Width           =   1335
   End
   Begin VB.Image Image1 
      Height          =   240
      Left            =   1920
      Picture         =   "Form3.frx":058A
      Top             =   1080
      Width           =   240
   End
End
Attribute VB_Name = "Form3"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = False
Attribute VB_PredeclaredId = True
Attribute VB_Exposed = False
Option Explicit

' ==================== API 声明（用于打开网址） ====================
Private Declare Function ShellExecute Lib "shell32.dll" Alias "ShellExecuteA" ( _
    ByVal hWnd As Long, ByVal lpOperation As String, ByVal lpFile As String, _
    ByVal lpParameters As String, ByVal lpDirectory As String, ByVal nShowCmd As Long) As Long

' ==================== WinHttp 请求相关声明 ====================
Private Declare Function MultiByteToWideChar Lib "kernel32" ( _
    ByVal CodePage As Long, ByVal dwFlags As Long, _
    ByVal lpMultiByteStr As Long, ByVal cchMultiByte As Long, _
    ByVal lpWideCharStr As Long, ByVal cchWideChar As Long) As Long
Private Const CP_UTF8 = 65001

' ==================== 辅助函数：UTF-8 转 VB Unicode ====================
Private Function Utf8ToUnicode(ByRef utf8Bytes() As Byte) As String
    On Error GoTo ErrHandler
    Dim lSize As Long
    lSize = UBound(utf8Bytes) - LBound(utf8Bytes) + 1
    If lSize <= 0 Then Exit Function
    
    Dim lWideSize As Long
    lWideSize = MultiByteToWideChar(CP_UTF8, 0, VarPtr(utf8Bytes(LBound(utf8Bytes))), lSize, 0, 0)
    If lWideSize <= 0 Then Exit Function
    
    Dim s As String
    s = Space(lWideSize)
    MultiByteToWideChar CP_UTF8, 0, VarPtr(utf8Bytes(LBound(utf8Bytes))), lSize, StrPtr(s), lWideSize
    Utf8ToUnicode = s
    Exit Function
ErrHandler:
    Utf8ToUnicode = ""
End Function

' ==================== 从注册表读取站点链接 ====================
Private Function GetApiBase() As String
    ' 与 Form2 保持一致，从注册表读取
    GetApiBase = GetSetting("EosMesh", "Settings", "ApiBase", "")
End Function

' ==================== 发送 GET 请求并返回 JSON 字符串 ====================
Private Function HttpGet(ByVal url As String) As String
    On Error GoTo ErrHandler
    Dim http As Object
    Set http = CreateObject("WinHttp.WinHttpRequest.5.1")
    http.Open "GET", url, False
    http.Send
    
    Dim respBytes() As Byte
    respBytes = http.responseBody
    HttpGet = Utf8ToUnicode(respBytes)
    Set http = Nothing
    Exit Function
ErrHandler:
    HttpGet = ""
End Function

' ==================== 提取 JSON 字符串值（简易版） ====================
Private Function ExtractJsonString(ByVal json As String, ByVal key As String) As String
    Dim pattern As String
    pattern = """" & key & """:"
    Dim pos As Integer
    pos = InStr(json, pattern)
    If pos = 0 Then Exit Function
    pos = pos + Len(pattern)
    ' 跳过空白
    Do While pos <= Len(json) And (Mid(json, pos, 1) = " " Or Mid(json, pos, 1) = vbTab)
        pos = pos + 1
    Loop
    If Mid(json, pos, 1) <> """" Then Exit Function
    Dim startQuote As Integer
    startQuote = pos + 1
    Dim endQuote As Integer
    endQuote = InStr(startQuote, json, """")
    If endQuote = 0 Then Exit Function
    ExtractJsonString = Mid(json, startQuote, endQuote - startQuote)
End Function

' ==================== 窗体加载事件：获取服务端信息并显示 ====================
Private Sub Form_Load()
    Dim apiBase As String
    apiBase = GetApiBase()
    If apiBase = "" Then
        Label2.Visible = False   ' 无站点链接，不显示
        Exit Sub
    End If
    
    ' 确保 API 链接末尾不缺少 ? 或 & 符号
    If InStr(apiBase, "?") = 0 Then
        apiBase = apiBase & "?"
    Else
        apiBase = apiBase & "&"
    End If
    
    ' 请求版本和类型
    Dim versionResp As String, typeResp As String
    versionResp = HttpGet(apiBase & "action=get_station_version")
    typeResp = HttpGet(apiBase & "action=get_server_type")
    
    Dim version As String
    Dim serverType As String
    version = ExtractJsonString(versionResp, "version")
    serverType = ExtractJsonString(typeResp, "type")
    
    If version <> "" And serverType <> "" Then
        Label2.Caption = version & " - " & serverType & "/Server"
        Label2.Visible = True
    Else
        Label2.Visible = False   ' 获取失败，不显示
    End If
End Sub

' ==================== 点击图标打开 GitHub ====================
Private Sub Image1_Click()
    ShellExecute 0, "open", "https://github.com/NTdebug145/EosMesh", vbNullString, vbNullString, 1
End Sub

