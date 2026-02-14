package app.botdrop;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for model list.
 */
public class ModelListAdapter extends RecyclerView.Adapter<ModelListAdapter.ViewHolder> {

    private List<ModelInfo> mModels = new ArrayList<>();
    private OnModelClickListener mListener;

    public interface OnModelClickListener {
        void onModelClick(ModelInfo model);
    }

    public ModelListAdapter(OnModelClickListener listener) {
        this.mListener = listener;
    }

    public void updateList(List<ModelInfo> models) {
        this.mModels = models;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_model, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ModelInfo model = mModels.get(position);
        if (model == null) {
            return;
        }

        String provider = model.provider == null ? "" : model.provider;
        String modelName = model.model == null ? "" : model.model;
        boolean isProviderRow = TextUtils.isEmpty(model.model);

        String title;
        String meta;
        if (isProviderRow) {
            title = provider;
            meta = "Select provider and choose model";
        } else {
            title = modelName;
            meta = provider;
        }

        holder.modelBadge.setText(shortLabel(provider, isProviderRow));
        holder.modelName.setText(title);
        holder.modelMeta.setText(meta);
        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onModelClick(model);
            }
        });
    }

    @Override
    public int getItemCount() {
        return mModels.size();
    }

    private String shortLabel(String provider, boolean providerRow) {
        if (provider == null || provider.isEmpty()) {
            return providerRow ? "P" : "M";
        }
        return provider.substring(0, 1).toUpperCase();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView modelName;
        TextView modelMeta;
        TextView modelBadge;

        ViewHolder(View itemView) {
            super(itemView);
            modelName = itemView.findViewById(R.id.model_name);
            modelMeta = itemView.findViewById(R.id.model_meta);
            modelBadge = itemView.findViewById(R.id.model_badge);
        }
    }
}
