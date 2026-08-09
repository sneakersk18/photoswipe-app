package com.photoswipe;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.bumptech.glide.Glide;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ImageView imageView;
    private TextView tvCounter;
    private GestureDetector gestureDetector;
    private List<String> photoPaths = new ArrayList<>();
    private int currentIndex = 0;
    private static final int PERMISSION_REQUEST = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        tvCounter = findViewById(R.id.tvCounter);

        gestureDetector = new GestureDetector(this, new SwipeListener());

        imageView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
        } else {
            loadPhotos();
        }
    }

    private void loadPhotos() {
        ContentResolver cr = getContentResolver();
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Images.Media.DATA};
        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";
        Cursor cursor = cr.query(uri, projection, null, null, sortOrder);
        if (cursor != null) {
            while (cursor.moveToNext()) {
                photoPaths.add(cursor.getString(0));
            }
            cursor.close();
        }
        if (!photoPaths.isEmpty()) showCurrentPhoto();
        else Toast.makeText(this, "No hay fotos", Toast.LENGTH_SHORT).show();
    }

    private void showCurrentPhoto() {
        if (currentIndex >= photoPaths.size()) {
            tvCounter.setText("¡Listo! Revisaste todas las fotos");
            imageView.setImageDrawable(null);
            return;
        }
        Glide.with(this).load(photoPaths.get(currentIndex)).into(imageView);
        tvCounter.setText((currentIndex + 1) + " / " + photoPaths.size());
    }

    private void keepPhoto() {
        Toast.makeText(this, "✓ Guardada", Toast.LENGTH_SHORT).show();
        currentIndex++;
        showCurrentPhoto();
    }

    private void deletePhoto() {
        String path = photoPaths.get(currentIndex);
        File file = new File(path);
        if (file.delete()) {
            getContentResolver().delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media.DATA + "=?", new String[]{path});
            Toast.makeText(this, "🗑 Eliminada", Toast.LENGTH_SHORT).show();
            photoPaths.remove(currentIndex);
            showCurrentPhoto();
        } else {
            Toast.makeText(this, "No se pudo eliminar", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            loadPhotos();
        }
    }

    private class SwipeListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY = 100;

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
            float diffX = e2.getX() - e1.getX();
            if (Math.abs(diffX) > Math.abs(e2.getY() - e1.getY())) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(vX) > SWIPE_VELOCITY) {
                    if (diffX > 0) keepPhoto();
                    else deletePhoto();
                    return true;
                }
            }
            return false;
        }
    }
}
