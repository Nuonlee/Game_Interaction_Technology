package com.example.virtualtouchpad

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.Toast

class LobbyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // 여기서 XML과 연결
        setContentView(R.layout.activity_lobby)

        val buttonStart = findViewById<Button>(R.id.button_start)
        val buttonSetting = findViewById<Button>(R.id.button_setting)
        buttonStart.setOnClickListener {
            // Move to execution of virtual touch
//            val intent1 = Intent(this, MainActivity::class.java)
//            startActivity(intent1)
            Toast.makeText(this, "현재 비활성화된 기능", Toast.LENGTH_SHORT).show()
        }
        buttonSetting.setOnClickListener {
            // Move to setting
            val intent2 = Intent(this, SettingActivity::class.java)
            startActivity(intent2)
        }
    }
}