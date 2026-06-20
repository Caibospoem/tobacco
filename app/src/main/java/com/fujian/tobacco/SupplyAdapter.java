package com.fujian.tobacco;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fujian.tobacco.data.model.SupplyItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 投放策略列表 Adapter（精简版）
 */
class SupplyAdapter extends RecyclerView.Adapter<SupplyAdapter.VH> {

    private List<SupplyItem> items;

    SupplyAdapter(List<SupplyItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    void updateData(List<SupplyItem> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_supply_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        SupplyItem item = items.get(pos);
        h.brand.setText(item.getBrandName());

        // 品类 + 区域 + 备注
        StringBuilder sub = new StringBuilder();
        if (!isEmpty(item.getTierCategory())) sub.append(item.getTierCategory());
        if (!isEmpty(item.getRegion())) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(item.getRegion());
        }
        if (!isEmpty(item.getSupplyNote())) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(item.getSupplyNote());
        }
        if (sub.length() > 0) {
            h.subtitle.setText(sub);
            h.subtitle.setVisibility(View.VISIBLE);
        } else {
            h.subtitle.setVisibility(View.GONE);
        }

        // 配额
        int quota = item.getPersonalQuota();
        if (quota > 0) {
            h.quota.setText(quota + " 条");
            h.quota.setTextColor(0xFF2E7D32);
        } else if ("不投".equals(item.getSupplyNote())) {
            h.quota.setText("暂不投放");
            h.quota.setTextColor(0xFF999999);
        } else {
            h.quota.setText("--");
            h.quota.setTextColor(0xFF999999);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView brand, subtitle, quota;

        VH(View v) {
            super(v);
            brand = v.findViewById(R.id.tv_brand);
            subtitle = v.findViewById(R.id.tv_subtitle);
            quota = v.findViewById(R.id.tv_quota);
        }
    }
}
