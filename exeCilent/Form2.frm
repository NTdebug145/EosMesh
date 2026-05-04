VERSION 5.00
Begin VB.Form Form2 
   BorderStyle     =   1  'Fixed Single
   Caption         =   "EMC - Chat"
   ClientHeight    =   5910
   ClientLeft      =   45
   ClientTop       =   390
   ClientWidth     =   9135
   Icon            =   "Form2.frx":0000
   LinkTopic       =   "Form2"
   MaxButton       =   0   'False
   MinButton       =   0   'False
   ScaleHeight     =   5910
   ScaleWidth      =   9135
   StartUpPosition =   2  '屏幕中心
   Begin VB.CommandButton Command3 
      Caption         =   "关于"
      Height          =   375
      Left            =   8520
      TabIndex        =   5
      Top             =   0
      Width           =   615
   End
   Begin VB.Timer Timer2 
      Left            =   6600
      Top             =   0
   End
   Begin VB.TextBox Text1 
      Height          =   375
      Left            =   1680
      TabIndex        =   4
      Top             =   5520
      Width           =   6255
   End
   Begin VB.CommandButton Command2 
      Caption         =   "发送"
      Height          =   375
      Left            =   7920
      TabIndex        =   3
      Top             =   5520
      Width           =   1215
   End
   Begin VB.ListBox List1 
      Height          =   5280
      Left            =   1680
      TabIndex        =   2
      Top             =   360
      Width           =   7455
   End
   Begin VB.CommandButton Command1 
      Caption         =   "刷新好友列表"
      Height          =   375
      Left            =   0
      TabIndex        =   1
      Top             =   0
      Width           =   1695
   End
   Begin VB.Timer Timer1 
      Left            =   6840
      Top             =   0
   End
   Begin VB.ListBox Listbox1 
      Height          =   5640
      Left            =   0
      TabIndex        =   0
      Top             =   360
      Width           =   1695
   End
End
Attribute VB_Name = "Form2"
Attribute VB_GlobalNameSpace = False
Attribute VB_Creatable = False
Attribute VB_PredeclaredId = True
Attribute VB_Exposed = False
Option Explicit

' ==================== API 声明 ====================
Private Declare Function SendMessage Lib "user32" Alias "SendMessageA" ( _
    ByVal hWnd As Long, ByVal wMsg As Long, ByVal wParam As Long, lParam As Any) As Long
Private Const LB_GETTOPINDEX = &H18E
Private Const LB_SETTOPINDEX = &H197

Private Declare Function MultiByteToWideChar Lib "kernel32" ( _
    ByVal CodePage As Long, ByVal dwFlags As Long, _
    ByVal lpMultiByteStr As Long, ByVal cchMultiByte As Long, _
    ByVal lpWideCharStr As Long, ByVal cchWideChar As Long) As Long
Private Const CP_UTF8 = 65001

' ==================== 公共变量 ====================
Public UserToken As String
Public UserUID As String
Public apiBase As String

' ==================== 聊天私有变量 ====================
Private currentFriendUid As String
Private currentFriendName As String
Private currentPage As Integer
Private totalPages As Integer
Private hasMore As Boolean
Private loadingMore As Boolean
Private latestMsgTime As Long
Private msgTimestamps() As Long
Private isAtBottom As Boolean

' ==================== UTF-8 → VB Unicode 字符串 ====================
Public Function Utf8ToUnicode(ByRef utf8Bytes() As Byte) As String
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

' ==================== 完整的 JSON 字符串解码（支持 \uXXXX 和常见转义） ====================
Public Function UnescapeJsonString(ByVal src As String) As String
    Dim i As Long
    Dim result As String
    Dim ch As String
    Dim nxt As String
    Dim hexStr As String
    Dim codePoint As Long
    
    i = 1
    result = ""
    Do While i <= Len(src)
        ch = Mid(src, i, 1)
        If ch = "\" And i + 1 <= Len(src) Then
            nxt = Mid(src, i + 1, 1)
            Select Case nxt
                Case "u"
                    ' \uXXXX 转义
                    If i + 5 <= Len(src) Then
                        hexStr = Mid(src, i + 2, 4)
                        If IsHex(hexStr) Then
                            codePoint = Val("&H" & hexStr)
                            result = result & ChrW(codePoint)
                            i = i + 6
                            GoTo NextChar
                        End If
                    End If
                    ' 不是合法的 \uXXXX，当作普通字符处理
                    result = result & ch
                    i = i + 1
                Case "n"
                    result = result & vbLf
                    i = i + 2
                Case "r"
                    result = result & vbCr
                    i = i + 2
                Case "t"
                    result = result & vbTab
                    i = i + 2
                Case "\\"
                    result = result & "\"
                    i = i + 2
                Case """"
                    result = result & """"
                    i = i + 2
                Case Else
                    ' 其他转义（如 \/）直接保留反斜杠和字符
                    result = result & nxt
                    i = i + 2
            End Select
        Else
            result = result & ch
            i = i + 1
        End If
NextChar:
    Loop
    UnescapeJsonString = result
End Function

' 辅助函数
Private Function IsHex(ByVal s As String) As Boolean
    If Len(s) <> 4 Then Exit Function
    Dim i As Integer
    For i = 1 To 4
        Dim c As String
        c = Mid(s, i, 1)
        If Not (c Like "[0-9A-Fa-f]") Then Exit Function
    Next
    IsHex = True
End Function

' ==================== 提取 JSON 字符串值（自动解码） ====================
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
    
    If Mid(json, pos, 1) <> """" Then Exit Function   ' 不是字符串类型
    Dim startQuote As Integer
    startQuote = pos + 1
    Dim endQuote As Integer
    endQuote = startQuote
    Do
        endQuote = InStr(endQuote + 1, json, """")
        If endQuote = 0 Then Exit Function
        ' 检查引号是否被转义
        Dim backslashCount As Integer
        backslashCount = 0
        Dim j As Integer
        j = endQuote - 1
        While j >= 1 And Mid(json, j, 1) = "\"
            backslashCount = backslashCount + 1
            j = j - 1
        Wend
        If backslashCount Mod 2 = 0 Then Exit Do   ' 偶数个反斜杠表示引号未转义
    Loop
    
    Dim rawValue As String
    rawValue = Mid(json, startQuote, endQuote - startQuote)
    ExtractJsonString = UnescapeJsonString(rawValue)
End Function

' ==================== 提取 JSON 数字或布尔值（直接返回原字符串） ====================
Private Function ExtractJsonNumber(ByVal json As String, ByVal key As String) As String
    Dim pattern As String
    pattern = """" & key & """:"
    Dim pos As Integer
    pos = InStr(json, pattern)
    If pos = 0 Then Exit Function
    pos = pos + Len(pattern)
    Do While pos <= Len(json) And (Mid(json, pos, 1) = " " Or Mid(json, pos, 1) = vbTab)
        pos = pos + 1
    Loop
    Dim endPos As Integer
    endPos = pos
    While endPos <= Len(json) And (Mid(json, endPos, 1) Like "[0-9\-\.]")
        endPos = endPos + 1
    Wend
    ExtractJsonNumber = Mid(json, pos, endPos - pos)
End Function

' ==================== 为了兼容旧代码，保留原 ExtractJsonValue 名称（内部调用新函数） ====================
Private Function ExtractJsonValue(ByVal json As String, ByVal key As String) As String
    ' 先尝试作为字符串提取
    Dim s As String
    s = ExtractJsonString(json, key)
    If s <> "" Then
        ExtractJsonValue = s
    Else
        ' 否则作为数字/布尔提取
        ExtractJsonValue = ExtractJsonNumber(json, key)
    End If
End Function

' ==================== 提取 JSON 数组（用于 data 字段等） ====================
Private Function ExtractJsonArray(ByVal json As String, ByVal key As String) As String
    Dim pattern As String
    pattern = """" & key & """:"
    Dim pos As Integer
    pos = InStr(json, pattern)
    If pos = 0 Then Exit Function
    pos = pos + Len(pattern)
    Do While pos <= Len(json) And (Mid(json, pos, 1) = " " Or Mid(json, pos, 1) = vbTab)
        pos = pos + 1
    Loop
    If Mid(json, pos, 1) <> "[" Then Exit Function
    Dim startBracket As Integer
    startBracket = pos
    Dim bracketCount As Integer
    bracketCount = 1
    Dim i As Integer
    For i = startBracket + 1 To Len(json)
        Dim ch As String
        ch = Mid(json, i, 1)
        If ch = "[" Then
            bracketCount = bracketCount + 1
        ElseIf ch = "]" Then
            bracketCount = bracketCount - 1
            If bracketCount = 0 Then
                ExtractJsonArray = Mid(json, startBracket, i - startBracket + 1)
                Exit Function
            End If
        End If
    Next
End Function

' ==================== 提取原始 JSON 值（不进行转义解码，用于嵌套结构） ====================
Private Function ExtractJsonValueRaw(ByVal json As String, ByVal key As String) As String
    Dim pattern As String
    pattern = """" & key & """:"
    Dim pos As Integer
    pos = InStr(json, pattern)
    If pos = 0 Then Exit Function
    pos = pos + Len(pattern)
    Do While pos <= Len(json) And (Mid(json, pos, 1) = " " Or Mid(json, pos, 1) = vbTab)
        pos = pos + 1
    Loop
    Dim ch As String
    ch = Mid(json, pos, 1)
    If ch = "{" Then
        Dim braceCount As Integer
        braceCount = 1
        Dim i As Integer
        For i = pos + 1 To Len(json)
            ch = Mid(json, i, 1)
            If ch = "{" Then
                braceCount = braceCount + 1
            ElseIf ch = "}" Then
                braceCount = braceCount - 1
                If braceCount = 0 Then
                    ExtractJsonValueRaw = Mid(json, pos, i - pos + 1)
                    Exit Function
                End If
            End If
        Next
    ElseIf ch = "[" Then
        Dim bracketCount As Integer
        bracketCount = 1
        For i = pos + 1 To Len(json)
            ch = Mid(json, i, 1)
            If ch = "[" Then
                bracketCount = bracketCount + 1
            ElseIf ch = "]" Then
                bracketCount = bracketCount - 1
                If bracketCount = 0 Then
                    ExtractJsonValueRaw = Mid(json, pos, i - pos + 1)
                    Exit Function
                End If
            End If
        Next
    ElseIf ch = """" Then
        Dim endQuote As Integer
        endQuote = InStr(pos + 1, json, """")
        If endQuote > 0 Then
            ExtractJsonValueRaw = Mid(json, pos, endQuote - pos + 1)
        End If
    Else
        Dim endPos As Integer
        endPos = pos
        While endPos <= Len(json) And (Mid(json, endPos, 1) Like "[0-9\-\.]")
            endPos = endPos + 1
        Wend
        ExtractJsonValueRaw = Mid(json, pos, endPos - pos)
    End If
End Function

' ==================== 辅助：提取整数 ====================
Private Function ExtractJsonInteger(ByVal json As String, ByVal key As String) As Integer
    Dim s As String
    s = ExtractJsonNumber(json, key)
    If s = "" Then
        ExtractJsonInteger = 0
    Else
        ExtractJsonInteger = CInt(s)
    End If
End Function

' ==================== 其他工具函数 ====================
Private Function IsSuccessResponse(ByVal json As String) As Boolean
    Dim code As String
    code = ExtractJsonNumber(json, "code")
    IsSuccessResponse = (code = "200")
End Function

Private Function FindMatchingBrace(ByVal text As String, ByVal startPos As Integer) As Integer
    Dim braceCount As Integer
    braceCount = 1
    Dim i As Integer
    For i = startPos + 1 To Len(text)
        Dim ch As String
        ch = Mid(text, i, 1)
        If ch = "{" Then
            braceCount = braceCount + 1
        ElseIf ch = "}" Then
            braceCount = braceCount - 1
            If braceCount = 0 Then
                FindMatchingBrace = i
                Exit Function
            End If
        End If
    Next
    FindMatchingBrace = 0
End Function

Private Function EscapeJsonString(ByVal s As String) As String
    s = Replace(s, "\", "\\")
    s = Replace(s, """", "\""")
    s = Replace(s, vbCrLf, "\n")
    s = Replace(s, vbCr, "\n")
    s = Replace(s, vbLf, "\n")
    EscapeJsonString = s
End Function

Private Function FormatTime(ByVal timestamp As Long) As String
    Dim dt As Date
    dt = DateAdd("s", timestamp, "1970/1/1 00:00:00")
    FormatTime = Format(dt, "HH:MM")
End Function

' ==================== 好友列表项提取 ====================
Private Function ExtractUsernameFromFriendItem(ByVal itemText As String) As String
    Dim pos As Integer
    pos = InStr(itemText, " (")
    If pos > 0 Then
        ExtractUsernameFromFriendItem = Left(itemText, pos - 1)
    Else
        ExtractUsernameFromFriendItem = itemText
    End If
End Function

Private Function ExtractUidFromFriendItem(ByVal itemText As String) As String
    Dim startPos As Integer, endPos As Integer
    startPos = InStr(itemText, "(")
    endPos = InStr(itemText, ")")
    If startPos > 0 And endPos > startPos Then
        ExtractUidFromFriendItem = Mid(itemText, startPos + 1, endPos - startPos - 1)
    Else
        ExtractUidFromFriendItem = ""
    End If
End Function

' ==================== 消息解析（使用新的解码函数） ====================
Private Function ParseMessagesFromJson(ByVal json As String, ByRef msgLines() As String, ByRef timestamps() As Long) As Integer
    Dim dataJson As String
    dataJson = ExtractJsonValueRaw(json, "data")
    If dataJson = "" Then
        ParseMessagesFromJson = 0
        Exit Function
    End If
    
    Dim messagesArray As String
    If Left(Trim(dataJson), 1) = "[" Then
        messagesArray = dataJson
    Else
        messagesArray = ExtractJsonArray(dataJson, "messages")
    End If
    
    If messagesArray = "" Then
        ParseMessagesFromJson = 0
        Exit Function
    End If
    
    Dim tempLines() As String, tempTimes() As Long
    ReDim tempLines(0 To 0)
    ReDim tempTimes(0 To 0)
    Dim count As Integer
    count = 0
    
    Dim startPos As Integer, endPos As Integer
    startPos = 2
    Do
        startPos = InStr(startPos, messagesArray, "{")
        If startPos = 0 Then Exit Do
        endPos = FindMatchingBrace(messagesArray, startPos)
        If endPos = 0 Then Exit Do
        Dim msgJson As String
        msgJson = Mid(messagesArray, startPos, endPos - startPos + 1)
        
        Dim fromUid As String, content As String, msgTime As Long
        fromUid = ExtractJsonString(msgJson, "from")      ' 使用解码版
        content = ExtractJsonString(msgJson, "content")   ' 使用解码版
        msgTime = CLng(ExtractJsonNumber(msgJson, "time"))
        
        Dim displayLine As String
        If fromUid = UserUID Then
            displayLine = "我: " & content & " (" & FormatTime(msgTime) & ")"
        Else
            displayLine = currentFriendName & ": " & content & " (" & FormatTime(msgTime) & ")"
        End If
        
        ReDim Preserve tempLines(0 To count)
        ReDim Preserve tempTimes(0 To count)
        tempLines(count) = displayLine
        tempTimes(count) = msgTime
        count = count + 1
        
        startPos = endPos + 1
    Loop
    
    msgLines = tempLines
    timestamps = tempTimes
    ParseMessagesFromJson = count
End Function

' ==================== 填充消息列表 ====================
Private Sub FillMessageList(ByVal jsonResp As String)
    Dim lines() As String, times() As Long
    Dim cnt As Integer
    cnt = ParseMessagesFromJson(jsonResp, lines, times)
    
    List1.Clear
    Erase msgTimestamps
    Dim i As Integer
    For i = 0 To cnt - 1
        List1.AddItem lines(i)
        AppendTimestamp times(i)
    Next
End Sub

Private Sub AppendTimestamp(ByVal ts As Long)
    Dim size As Integer
    On Error Resume Next
    size = UBound(msgTimestamps) + 1
    If Err.Number <> 0 Then
        size = 0
        Erase msgTimestamps
    End If
    On Error GoTo 0
    ReDim Preserve msgTimestamps(0 To size)
    msgTimestamps(size) = ts
End Sub

' ==================== 加载好友列表 ====================
Private Sub LoadFriends()
    On Error GoTo ErrHandler
    Dim url As String
    url = apiBase & IIf(InStr(apiBase, "?") > 0, "&", "?") & "action=get_friends"
    
    Dim http As Object
    Set http = CreateObject("WinHttp.WinHttpRequest.5.1")
    http.Open "GET", url, False
    http.setRequestHeader "Authorization", UserToken
    http.Send
    
    Dim respBytes() As Byte
    respBytes = http.responseBody
    Dim resp As String
    resp = Utf8ToUnicode(respBytes)
    
    Set http = Nothing
    
    If Not IsSuccessResponse(resp) Then
        MsgBox "获取好友列表失败", vbExclamation
        Exit Sub
    End If
    
    Dim friendsArray As String
    friendsArray = ExtractJsonArray(resp, "data")
    If friendsArray = "" Then
        Listbox1.Clear
        Exit Sub
    End If
    
    Listbox1.Clear
    Dim startPos As Integer, endPos As Integer
    startPos = 2
    Do
        startPos = InStr(startPos, friendsArray, "{")
        If startPos = 0 Then Exit Do
        endPos = FindMatchingBrace(friendsArray, startPos)
        If endPos = 0 Then Exit Do
        Dim itemJson As String
        itemJson = Mid(friendsArray, startPos, endPos - startPos + 1)
        
        Dim friendUid As String, friendName As String
        friendUid = ExtractJsonString(itemJson, "uid")
        friendName = ExtractJsonString(itemJson, "username")
        
        If friendUid <> "" And friendName <> "" Then
            Listbox1.AddItem friendName & " (" & friendUid & ")"
        End If
        startPos = endPos + 1
    Loop
    Exit Sub
ErrHandler:
    MsgBox "加载好友失败", vbExclamation
End Sub

' ==================== 发送消息 ====================
Private Sub SendMessageToServer()
    Dim content As String
    content = Trim(Text1.text)
    If content = "" Then Exit Sub
    If currentFriendUid = "" Then
        MsgBox "请先选择一个好友", vbExclamation
        Exit Sub
    End If
    
    Dim respJson As String
    respJson = SendMessageRequest(currentFriendUid, content)
    
    If IsSuccessResponse(respJson) Then
        Text1.text = ""
        Dim t As Double
        t = Timer
        While Timer - t < 0.1
            DoEvents
        Wend
        PollNewMessages
    Else
        Dim errMsg As String
        errMsg = ExtractJsonString(respJson, "msg")
        If errMsg = "" Then errMsg = "发送失败"
        MsgBox errMsg, vbExclamation
    End If
End Sub

Private Function SendMessageRequest(ByVal toUid As String, ByVal content As String) As String
    On Error GoTo ErrHandler
    Dim url As String
    url = apiBase & IIf(InStr(apiBase, "?") > 0, "&", "?") & "action=send_message"
    
    Dim jsonBody As String
    jsonBody = "{" & _
               Chr(34) & "to_uid" & Chr(34) & ":" & Chr(34) & EscapeJsonString(toUid) & Chr(34) & "," & _
               Chr(34) & "content" & Chr(34) & ":" & Chr(34) & EscapeJsonString(content) & Chr(34) & _
               "}"
    
    Dim http As Object
    Set http = CreateObject("WinHttp.WinHttpRequest.5.1")
    http.Open "POST", url, False
    http.setRequestHeader "Content-Type", "application/json"
    http.setRequestHeader "Authorization", UserToken
    http.Send jsonBody
    
    Dim respBytes() As Byte
    respBytes = http.responseBody
    SendMessageRequest = Utf8ToUnicode(respBytes)
    
    Set http = Nothing
    Exit Function
ErrHandler:
    SendMessageRequest = "{""code"":500,""msg"":""网络错误""}"
End Function

' ==================== 获取消息（分页、增量） ====================
Private Function GetMessagesRequest(ByVal friendUid As String, ByVal page As Integer, ByVal limit As Integer) As String
    On Error GoTo ErrHandler
    Dim url As String
    url = apiBase & IIf(InStr(apiBase, "?") > 0, "&", "?") & _
          "action=get_messages&friend_uid=" & friendUid & "&page=" & page & "&limit=" & limit
    
    Dim http As Object
    Set http = CreateObject("WinHttp.WinHttpRequest.5.1")
    http.Open "GET", url, False
    http.setRequestHeader "Authorization", UserToken
    http.Send
    
    Dim respBytes() As Byte
    respBytes = http.responseBody
    GetMessagesRequest = Utf8ToUnicode(respBytes)
    
    Set http = Nothing
    Exit Function
ErrHandler:
    GetMessagesRequest = "{""code"":500,""msg"":""网络错误""}"
End Function

Private Function GetMessagesSinceRequest(ByVal friendUid As String, ByVal sinceTime As Long) As String
    On Error GoTo ErrHandler
    Dim url As String
    url = apiBase & IIf(InStr(apiBase, "?") > 0, "&", "?") & _
          "action=get_messages&friend_uid=" & friendUid & "&since=" & sinceTime
    
    Dim http As Object
    Set http = CreateObject("WinHttp.WinHttpRequest.5.1")
    http.Open "GET", url, False
    http.setRequestHeader "Authorization", UserToken
    http.Send
    
    Dim respBytes() As Byte
    respBytes = http.responseBody
    GetMessagesSinceRequest = Utf8ToUnicode(respBytes)
    
    Set http = Nothing
    Exit Function
ErrHandler:
    GetMessagesSinceRequest = "{""code"":500,""msg"":""网络错误""}"
End Function

' ==================== 加载历史消息（分页） ====================
Private Sub LoadMessages()
    If currentFriendUid = "" Then Exit Sub
    
    Dim firstResp As String
    firstResp = GetMessagesRequest(currentFriendUid, 1, 1)
    If Not IsSuccessResponse(firstResp) Then
        MsgBox "获取消息总数失败", vbExclamation
        Exit Sub
    End If
    
    Dim total As Integer
    total = ExtractJsonInteger(firstResp, "total")
    If total = 0 Then
        List1.Clear
        Erase msgTimestamps
        currentPage = 0
        totalPages = 0
        hasMore = False
        latestMsgTime = 0
        Exit Sub
    End If
    
    Dim limit As Integer
    limit = 20
    totalPages = (total + limit - 1) \ limit
    currentPage = totalPages
    hasMore = (currentPage > 1)
    
    Dim pageResp As String
    pageResp = GetMessagesRequest(currentFriendUid, currentPage, limit)
    If Not IsSuccessResponse(pageResp) Then
        MsgBox "加载消息失败", vbExclamation
        Exit Sub
    End If
    
    FillMessageList pageResp
    
    If List1.ListCount > 0 Then
        latestMsgTime = msgTimestamps(UBound(msgTimestamps))
    End If
    
    ScrollToBottom
End Sub

Private Sub LoadMoreMessages()
    If loadingMore Then Exit Sub
    If Not hasMore Then Exit Sub
    If currentPage <= 1 Then Exit Sub
    
    loadingMore = True
    Dim oldTopIndex As Long
    oldTopIndex = SendMessage(List1.hWnd, LB_GETTOPINDEX, 0, ByVal 0&)
    
    Dim prevPage As Integer
    prevPage = currentPage - 1
    Dim resp As String
    resp = GetMessagesRequest(currentFriendUid, prevPage, 20)
    
    If IsSuccessResponse(resp) Then
        Dim oldMessages() As String
        Dim oldTimestamps() As Long
        Dim i As Integer
        ReDim oldMessages(0 To List1.ListCount - 1)
        ReDim oldTimestamps(0 To UBound(msgTimestamps))
        For i = 0 To List1.ListCount - 1
            oldMessages(i) = List1.List(i)
            oldTimestamps(i) = msgTimestamps(i)
        Next
        
        Dim newMessages() As String
        Dim newTimestamps() As Long
        Dim newCount As Integer
        newCount = ParseMessagesFromJson(resp, newMessages, newTimestamps)
        
        List1.Clear
        Erase msgTimestamps
        For i = 0 To newCount - 1
            List1.AddItem newMessages(i)
            AppendTimestamp newTimestamps(i)
        Next
        For i = 0 To UBound(oldMessages)
            List1.AddItem oldMessages(i)
            AppendTimestamp oldTimestamps(i)
        Next
        
        currentPage = prevPage
        hasMore = (prevPage > 1)
        
        Dim newTopIndex As Long
        newTopIndex = oldTopIndex + newCount
        If newTopIndex < List1.ListCount Then
            SendMessage List1.hWnd, LB_SETTOPINDEX, newTopIndex, ByVal 0&
        End If
    End If
    
    loadingMore = False
End Sub

' ==================== 轮询新消息 ====================
Private Sub PollNewMessages()
    If currentFriendUid = "" Then Exit Sub
    If latestMsgTime = 0 Then Exit Sub
    
    Dim sinceTime As Long
    sinceTime = latestMsgTime - 1
    If sinceTime < 0 Then sinceTime = 0
    Dim resp As String
    resp = GetMessagesSinceRequest(currentFriendUid, sinceTime)
    If Not IsSuccessResponse(resp) Then Exit Sub
    
    Dim newMsgs() As String
    Dim newTimes() As Long
    Dim newCount As Integer
    newCount = ParseMessagesFromJson(resp, newMsgs, newTimes)
    
    If newCount > 0 Then
        Dim i As Integer, j As Integer
        For i = 0 To newCount - 1
            Dim isDuplicate As Boolean
            isDuplicate = False
            For j = 0 To List1.ListCount - 1
                If msgTimestamps(j) = newTimes(i) And List1.List(j) = newMsgs(i) Then
                    isDuplicate = True
                    Exit For
                End If
            Next
            If Not isDuplicate Then
                List1.AddItem newMsgs(i)
                AppendTimestamp newTimes(i)
            End If
        Next
        
        If newTimes(newCount - 1) > latestMsgTime Then
            latestMsgTime = newTimes(newCount - 1)
        End If
        
        CheckIfAtBottom
        If isAtBottom Then
            ScrollToBottom
        End If
    End If
End Sub

' ==================== 滚动控制 ====================
Private Sub CheckIfAtBottom()
    If List1.ListCount = 0 Then
        isAtBottom = True
        Exit Sub
    End If
    Dim topIdx As Long
    topIdx = SendMessage(List1.hWnd, LB_GETTOPINDEX, 0, ByVal 0&)
    isAtBottom = (topIdx >= List1.ListCount - 3)
End Sub

Private Sub ScrollToBottom()
    If List1.ListCount > 0 Then
        SendMessage List1.hWnd, LB_SETTOPINDEX, List1.ListCount - 1, ByVal 0&
    End If
End Sub

' ==================== 窗体事件 ====================
Private Sub Form_Load()
    apiBase = GetSetting("EosMesh", "Settings", "ApiBase", "")
    UserToken = GetSetting("EosMesh", "User", "Token", "")
    UserUID = GetSetting("EosMesh", "User", "UID", "")
    
    If apiBase = "" Then apiBase = Me.apiBase
    If UserToken = "" Then UserToken = Me.UserToken
    If UserUID = "" Then UserUID = Me.UserUID
    
    If apiBase = "" Or UserToken = "" Then
        MsgBox "未找到登录信息，请先登录。", vbExclamation, "错误"
        Unload Me
        Exit Sub
    End If
    
    Me.Caption = "EosMesh 聊天客户端"
    
    List1.FontName = "微软雅黑"
    List1.FontSize = 9
    Listbox1.FontName = "微软雅黑"
    Listbox1.FontSize = 9
    Text1.FontName = "微软雅黑"
    Text1.FontSize = 9
    
    Timer1.Interval = 3000
    Timer1.Enabled = False
    Timer2.Interval = 200
    Timer2.Enabled = True
    
    isAtBottom = True
    
    LoadFriends
End Sub

Private Sub Form_Unload(Cancel As Integer)
    End
End Sub

Private Sub Command1_Click()
    LoadFriends
End Sub

Private Sub Command2_Click()
    SendMessageToServer
End Sub

Private Sub ListBox1_Click()
    Dim idx As Integer
    idx = Listbox1.ListIndex
    If idx = -1 Then Exit Sub
    
    Dim selectedText As String
    selectedText = Listbox1.List(idx)
    currentFriendName = ExtractUsernameFromFriendItem(selectedText)
    currentFriendUid = ExtractUidFromFriendItem(selectedText)
    
    If currentFriendUid = "" Then Exit Sub
    
    List1.Clear
    Erase msgTimestamps
    currentPage = 0
    totalPages = 0
    hasMore = False
    loadingMore = False
    latestMsgTime = 0
    
    LoadMessages
    Timer1.Enabled = True
End Sub

Private Sub Text1_KeyPress(KeyAscii As Integer)
    If KeyAscii = vbKeyReturn Then
        KeyAscii = 0
        SendMessageToServer
    End If
End Sub

Private Sub Timer1_Timer()
    PollNewMessages
End Sub

Private Sub Timer2_Timer()
    If currentFriendUid = "" Then Exit Sub
    If List1.ListCount = 0 Then Exit Sub
    
    Dim topIndex As Long
    topIndex = SendMessage(List1.hWnd, LB_GETTOPINDEX, 0, ByVal 0&)
    
    If topIndex <= 1 And hasMore And Not loadingMore Then
        LoadMoreMessages
    End If
End Sub

' 窗口2（Form2）中已有的代码保持不变
' 只需在末尾添加以下过程

Private Sub Command3_Click()
    ' 保留窗口2，显示窗口3
    Form3.Show vbModeless
End Sub

