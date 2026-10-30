package com.ratul.samsungremote

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val textView = TextView(this).apply {
            text = "Samsung Remote App - Pipeline Ready!"
            textSize = 20f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        setContentView(textView)
    }
}
