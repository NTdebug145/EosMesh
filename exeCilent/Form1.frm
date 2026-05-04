VERSION 5.00
Begin VB.Form Form1 
   BorderStyle     =   1  'Fixed Single
   Caption         =   "EMC"
   ClientHeight    =   2385
   ClientLeft      =   45
   ClientTop       =   390
   ClientWidth     =   2535
   Icon            =   "Form1.frx":0000
   LinkTopic       =   "Form1"
   MaxButton       =   0   'False
   MinButton       =   0   'False
   ScaleHeight     =   2385
   ScaleWidth      =   2535
   StartUpPosition =   2  '屏幕中心
   Begin VB.CommandButton Command1 
      Caption         =   "连接"
      Height          =   375
      Left            =   120
      TabIndex        =   3
      Top             =   1920
      Width           =   2295
   End
   Begin VB.TextBox Text3 
      Height          =   270
      Left            =   120
      TabIndex        =   2
      Top             =   1560
      Width           =   2295
   End
   Begin VB.TextBox Text2 
      Height          =   270
      Left            =   120
      TabIndex        =   1
      Top             =   960
      Width           =   2295
   End
   Begin VB.TextBox Text1 
      Height          =   270
      Left            =   120
      TabIndex        =   0
      Top             =   360
      Width           =   2295
   End
   Begin VB.Label Label3 
      Caption         =   "密码"
      Height          =   255
      Left            =   120
      TabIndex        =   6
      Top             =   1320
      Width           =   975
   End
   Begin VB.Label Label2 
      Caption         =   "用户名"
      Height          =   375
      Left            =   120
      TabIndex        =   5
      Top             =   720
      Width           =   855
   End
   Begin VB.Label Label1 
      Caption         =   "站点API"
      Height          =   255
      Left            =   120
      TabIndex        =   4
      Top             =   120
      Width           =   855
   End
End
Attribute VB_Name = "Form1"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = False
Attribute VB_PredeclaredId = True
Attribute VB_Exposed = False
Option Explicit

Private Sub Command1_Click()
    Dim apiUrl As String, username As String, password As String
    apiUrl = Trim(Text1.text)
    username = Trim(Text2.text)
    password = Trim(Text3.text)

    ' 输入校验
    If apiUrl = "" Then
        MsgBox "请输入站点API地址", vbExclamation, "提示"
        Exit Sub
    End If
    If username = "" Or password = "" Then
        MsgBox "请输入用户名和密码", vbExclamation, "提示"
        Exit Sub
    End If

    ' 禁用按钮，防止重复点击
    Command1.Enabled = False
    Command1.Caption = "登录中..."

    ' 发送登录请求
    Dim result As String
    result = SendLoginRequest(apiUrl, username, password)

    ' 处理响应
    If IsLoginSuccess(result) Then
        Dim token As String, uid As String
        token = ExtractJsonValue(result, "token")
        uid = ExtractJsonValue(result, "uid")

        ' 保存到注册表
        SaveSetting "EosMesh", "Settings", "ApiBase", apiUrl
        SaveSetting "EosMesh", "User", "Token", token
        SaveSetting "EosMesh", "User", "UID", uid

        ' 传递到 Form2 的公共变量
        Form2.UserToken = token
        Form2.UserUID = uid

        ' 显示主窗体，关闭登录窗体
        Form2.Show
        Unload Me
    Else
        Dim errMsg As String
        errMsg = ExtractJsonValue(result, "msg")
        If errMsg = "" Then errMsg = "登录失败，请检查用户名或密码"
        MsgBox "登录失败：" & errMsg, vbCritical, "错误"
        Command1.Enabled = True
        Command1.Caption = "登录"
    End If
End Sub

' ==================== 网络请求与 JSON 解析 ====================

Private Function SendLoginRequest(ByVal baseUrl As String, ByVal username As String, ByVal password As String) As String
    On Error GoTo ErrHandler
    Dim requestUrl As String
    If InStr(baseUrl, "?") > 0 Then
        requestUrl = baseUrl & "&action=login"
    Else
        requestUrl = baseUrl & "?action=login"
    End If

    Dim jsonBody As String
    jsonBody = "{" & _
               Chr(34) & "username" & Chr(34) & ":" & Chr(34) & EscapeJsonString(username) & Chr(34) & "," & _
               Chr(34) & "password" & Chr(34) & ":" & Chr(34) & EscapeJsonString(password) & Chr(34) & _
               "}"

    Dim http As Object
    Set http = CreateObject("WinHttp.WinHttpRequest.5.1")
    http.Open "POST", requestUrl, False
    http.setRequestHeader "Content-Type", "application/json"
    http.Send jsonBody

    SendLoginRequest = http.responseText
    Set http = Nothing
    Exit Function
ErrHandler:
    SendLoginRequest = "{""code"":500,""msg"":""网络错误: " & Replace(Err.Description, """", "'") & """}"
End Function

Private Function IsLoginSuccess(ByVal response As String) As Boolean
    IsLoginSuccess = (InStr(1, response, """code"":200", vbTextCompare) > 0) Or _
                     (InStr(1, response, """code"": 200", vbTextCompare) > 0)
End Function

Private Function ExtractJsonValue(ByVal json As String, ByVal key As String) As String
    Dim pattern As String
    pattern = """" & key & """:"""
    Dim p As Integer
    p = InStr(1, json, pattern, vbTextCompare)
    If p > 0 Then
        Dim startPos As Integer, endPos As Integer
        startPos = p + Len(pattern)
        endPos = InStr(startPos, json, """")
        If endPos > startPos Then
            ExtractJsonValue = Mid(json, startPos, endPos - startPos)
        End If
    Else
        pattern = """" & key & """:"
        p = InStr(1, json, pattern, vbTextCompare)
        If p > 0 Then
            startPos = p + Len(pattern)
            endPos = InStr(startPos, json, ",")
            If endPos = 0 Then endPos = InStr(startPos, json, "}")
            If endPos > startPos Then
                ExtractJsonValue = Trim(Mid(json, startPos, endPos - startPos))
            End If
        End If
    End If
End Function

Private Function EscapeJsonString(ByVal str As String) As String
    str = Replace(str, "\", "\\")
    str = Replace(str, Chr(34), "\" & Chr(34))
    EscapeJsonString = str
End Function

