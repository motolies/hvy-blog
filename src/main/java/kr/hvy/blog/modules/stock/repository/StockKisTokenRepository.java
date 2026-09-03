package kr.hvy.blog.modules.stock.repository;

import kr.hvy.blog.modules.stock.domain.entity.StockKisToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockKisTokenRepository extends JpaRepository<StockKisToken, String> {
}
