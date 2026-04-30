package com.nt.eosmesh

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class BlankActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blank)
    }

    companion object {
        fun newIntent(context: Context) = Intent(context, BlankActivity::class.java)
    }
}