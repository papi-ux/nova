package com.papi.nova.grid;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.papi.nova.R;

import java.util.ArrayList;

import java.util.List;

public abstract class GenericGridAdapter<T> extends RecyclerView.Adapter<GenericGridAdapter.ViewHolder> {
    protected final Context context;
    private int layoutId;
    public final ArrayList<T> itemList = new ArrayList<>();
    private final LayoutInflater inflater;

    public interface OnItemClickListener<T> {
        void onItemClick(T item);
    }
    
    private OnItemClickListener<T> clickListener;

    public void setOnItemClickListener(OnItemClickListener<T> listener) {
        this.clickListener = listener;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public ImageView imgView;
        public RelativeLayout gridMask;
        public ImageView overlayView;
        public TextView txtView;
        public ProgressBar prgView;

        public ViewHolder(View itemView) {
            super(itemView);
            imgView = itemView.findViewById(R.id.grid_image);
            gridMask = itemView.findViewById(R.id.grid_mask);
            overlayView = itemView.findViewById(R.id.grid_overlay);
            txtView = itemView.findViewById(R.id.grid_text);
            prgView = itemView.findViewById(R.id.grid_spinner);
        }
    }

    GenericGridAdapter(Context context, int layoutId) {
        this.context = context;
        this.layoutId = layoutId;

        this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    public void setItems(List<T> items) {
        itemList.clear();
        if (items != null) {
            itemList.addAll(items);
        }
        notifyDataSetChanged();
    }

    void setLayoutId(int layoutId) {
        if (layoutId != this.layoutId) {
            this.layoutId = layoutId;

            // Force the view to be redrawn with the new layout
            notifyDataSetChanged();
        }
    }

    public void clear() {
        itemList.clear();
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    public T getItem(int i) {
        return itemList.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    public abstract void populateView(View parentView, ImageView imgView, RelativeLayout gridMask, ProgressBar prgView, TextView txtView, ImageView overlayView, T obj);

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = inflater.inflate(layoutId, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        T item = itemList.get(position);
        populateView(holder.itemView, holder.imgView, holder.gridMask, holder.prgView, holder.txtView, holder.overlayView, item);
        
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onItemClick(item);
            }
        });
    }
}
