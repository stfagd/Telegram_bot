package com.example.languageteacherbot.repository;

import com.example.languageteacherbot.entity.UserFavoriteWord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserFavoriteWordRepository extends JpaRepository<UserFavoriteWord, Long> {
    List<UserFavoriteWord> findByUserChatId(Long userChatId);
    Optional<UserFavoriteWord> findByUserChatIdAndWordId(Long userChatId, Long wordId);
    void deleteByUserChatIdAndWordId(Long userChatId, Long wordId);
}