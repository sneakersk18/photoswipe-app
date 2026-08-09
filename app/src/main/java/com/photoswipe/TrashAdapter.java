package com.photoswipe;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import java.io.File;
import java.util.List;

public class TrashAdapter extends RecyclerView.Adapter<TrashAdapter.TrashViewHolder> {

    public interface TrashCallback {
        void onRestore(File file);
        void onDelete(File file);
    }

    private final List<File> files;
    private final TrashCallback callback;

    public TrashAdapter(List<File> files, TrashCallback callback) {
        this.files = files;
        this.callback = callback;
    }

    @NonNull
    @Override
    public TrashViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_trash, parent, false);
        return new TrashViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TrashViewHolder holder, int position) {
        File file = files.get(position);

        // Enforce square thumbnail: set height = measured width.
        // ViewTreeObserver fires after first layout so we can read the width.
        holder.thumbnail.post(() -> {
            int width = holder.thumbnail.getWidth();
            if (width > 0) {
                ViewGroup.LayoutParams lp = holder.thumbnail.getLayoutParams();
                lp.height = width;
                holder.thumbnail.setLayoutParams(lp);
            }
        });

        // Load thumbnail with Glide; videos get a frame grab automatically
        Glide.with(holder.thumbnail.getContext())
                .load(file)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .placeholder(android.R.color.darker_gray)
                .into(holder.thumbnail);

        // Show just the media filename
        String displayName = file.getName();
        holder.tvFilename.setText(displayName);

        holder.btnRestore.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            File f = files.get(pos);
            files.remove(pos);
            notifyItemRemoved(pos);
            callback.onRestore(f);
        });

        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            File f = files.get(pos);
            files.remove(pos);
            notifyItemRemoved(pos);
            callback.onDelete(f);
        });
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    static class TrashViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView tvFilename;
        final Button btnRestore;
        final Button btnDelete;

        TrashViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail  = itemView.findViewById(R.id.ivThumbnail);
            tvFilename = itemView.findViewById(R.id.tvFilename);
            btnRestore = itemView.findViewById(R.id.btnRestore);
            btnDelete  = itemView.findViewById(R.id.btnDeletePermanent);
        }
    }
}
