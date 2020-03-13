package com.coinsquare;

import android.content.Intent;
import android.net.Uri;

public class AtmDeposit
{
    // static variable single_instance of type Singleton
    private static AtmDeposit single_instance = null;

    // variable of type String
    private String action = null;
    private Uri data = null;

    // private constructor restricted to this class itself
    private AtmDeposit() {

    }

    // static method to create instance of Singleton class
    public static AtmDeposit getInstance()
    {
        if (single_instance == null)
            single_instance = new AtmDeposit();

        return single_instance;
    }

    public void setIntent(Intent intent) {
        if (intent != null) {
            this.action = intent.getAction();
            this.data = intent.getData();
        }
    }

    public String getAction() {
        return action;
    }

    public Uri getData() {
        return data;
    }

    public String getAddress(){
        return data.getQueryParameter("address");
    }

    public String getAmount(){
        return data.getQueryParameter("amount");
    }
}