package com.nt.eosmesh

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class FriendListActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 临时布局，后续可改为类似好友列表的界面
        setContentView(R.layout.activity_friend_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}