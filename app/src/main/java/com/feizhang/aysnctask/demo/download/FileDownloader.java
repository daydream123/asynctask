package com.feizhang.aysnctask.demo.download;

import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;

import com.feizhang.aysnctask.demo.AsyncTask;

import java.io.File;
import java.io.IOException;

/**
 * A file download utils which can notify download status like: progress update,
 * error and complete.
 */
public abstract class FileDownloader extends AsyncTask<Void, Integer, String, File> {
	private String mDownloadUrl;
	private String mStorePath;

	public FileDownloader(AppCompatActivity activity, String downloadUrl, String storePath) {
		super(activity);
		mDownloadUrl = downloadUrl;
		mStorePath = storePath;
	}

	public FileDownloader(Fragment fragment, String downloadUrl, String storePath) {
		super(fragment);
		mDownloadUrl = downloadUrl;
		mStorePath = storePath;
	}

	public FileDownloader(View view, String downloadUrl, String storePath) {
		super(view);
		mDownloadUrl = downloadUrl;
		mStorePath = storePath;
	}

	public FileDownloader(Tracker tracker, String downloadUrl, String storePath) {
		super(tracker);
		mDownloadUrl = downloadUrl;
		mStorePath = storePath;
	}

	@Override
	protected File doInBackground(Void... params) {
		if(TextUtils.isEmpty(mDownloadUrl)){
			throw new RuntimeException("download url cannot be empty or null.");
		}

		if(mStorePath == null){
			throw new RuntimeException("file to store cannot be null.");
		}

		try {
			return DownloadUtils.download(mDownloadUrl, mStorePath,
					new OnProgressListener() {

						@Override
						public void onProgress(int percentage) {
							publishProgress(percentage);
						}

						@Override
						public void onError(String errorMsg) {
							postError(errorMsg);
						}

						@Override
						public void onCompleted() {
							// do nothing, onSuccess() will be
							// executed instead.
						}
					});
		} catch (IOException e) {
			postError(e.getMessage());
			return null;
		}
	}

	/**
	 * Start download task.
	 */
	public void startDownload() {
		setDefaultExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
		execute();
	}
}