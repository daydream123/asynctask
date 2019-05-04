package com.feizhang.aysnctask.demo;

import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Toast;

import com.feizhang.aysnctask.demo.download.FileDownloader;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private AlertDialog mDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.downloadBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testDownload();
            }
        });
    }

    private void testDownload(){
        String url = "http://codown.youdao.com/dictmobile/youdaodict_android_youdaoweb.apk";
        String storePath = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();

        new FileDownloader(this, url, storePath){

            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                if (mDialog == null){
                    mDialog = new AlertDialog.Builder(MainActivity.this).create();
                    mDialog.setMessage("download...");
                    mDialog.setCancelable(false);
                }
                mDialog.show();
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                super.onProgressUpdate(values);
                mDialog.setMessage("download..." + values[0]);
            }

            @Override
            protected void onError(String s) {
                super.onError(s);
                mDialog.dismiss();
                Toast.makeText(MainActivity.this, "download failed: " + s, Toast.LENGTH_SHORT).show();
            }

            @Override
            protected void onCancelled() {
                super.onCancelled();
                mDialog.dismiss();
                Toast.makeText(MainActivity.this, "download canceled", Toast.LENGTH_SHORT).show();
            }

            @Override
            protected void onSuccess(File file) {
                super.onSuccess(file);
                mDialog.dismiss();
                Toast.makeText(MainActivity.this, "download success", Toast.LENGTH_SHORT).show();
            }
        }.startDownload();
    }
}
