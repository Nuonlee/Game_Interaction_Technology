package com.example.virtualtouchpad

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.EditText

class SettingActivity : AppCompatActivity() {

    private lateinit var textIsCamCalibed: TextView
    private lateinit var textIsHandCalibed: TextView

    private lateinit var inputEstIter : EditText
    private lateinit var inputEstSamplePerIter : EditText

    private lateinit var inputFilterBeta : EditText
    private lateinit var inputFilterDCutoff : EditText
    private lateinit var inputFilterMinCutoff : EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContentView(R.layout.activity_setting)

        val buttonCamCalib = findViewById<Button>(R.id.Button_CamCalib)
        val buttonHandCalib = findViewById<Button>(R.id.Button_HandCalib)
        val buttonBackward = findViewById<ImageButton>(R.id.button_back_setting)

        buttonCamCalib.setOnClickListener {
            val intent1 = Intent(this, CalibCamActivity::class.java)
            startActivity(intent1)
        }
        buttonHandCalib.setOnClickListener {
            val intent2 = Intent(this, CalibHandActivity::class.java)
            startActivity(intent2)
        }
        buttonBackward.setOnClickListener {
            finish()
        }

        textIsCamCalibed = findViewById(R.id.Text_IsCamCalib)
        textIsHandCalibed = findViewById(R.id.Text_IsHandCalib)

        inputEstIter = findViewById(R.id.InputEstIterNum)
        inputEstSamplePerIter = findViewById(R.id.InputSamplePerIter)

        inputFilterBeta = findViewById(R.id.InputFilterBeta)
        inputFilterDCutoff = findViewById(R.id.InputFilterDCutoff)
        inputFilterMinCutoff = findViewById(R.id.InputFilterMinCutoff)
    }

    override fun onStart() {
        super.onStart()
        //loadSetting()

    }

    private fun loadSetting() {
        // 설정값들 불러오기

    }
}