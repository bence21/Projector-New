package com.bence.songbook.api.retrofit;

import com.bence.projector.common.dto.FavouriteSongDTO;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface FavouriteSongApi {
    @POST("/user/api/favouriteSongs")
    Call<List<FavouriteSongDTO>> uploadFavouriteSong(@Body List<FavouriteSongDTO> favouriteSongDTO);

    @GET("/user/api/favouriteSongs/{modifiedDate}")
    Call<List<FavouriteSongDTO>> getFavouriteSongsAfterModifiedDate(@Path("modifiedDate") Long modifiedDate);
}
