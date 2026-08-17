package com.my.custom.claudepersonalassistant.mcp.domain.grocery;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.my.custom.claudepersonalassistant.mcp.persistence.GroceryEntity;
import com.my.custom.claudepersonalassistant.mcp.persistence.GroceryRepository;

/**
 * The groceries store over JPA.
 *
 * <p><strong>{@code @Transactional} is not decoration here.</strong> Tools are invoked straight from
 * the MCP HTTP endpoint with no transaction in scope, so without it every write would run in its own
 * implicit one and a bulk insert could land half-applied. It is the same hazard {@code
 * ToolEventPublisher} guards against from the other direction.
 *
 * <p>Categories are lower-cased and trimmed on the way in. A free-text category was chosen over an
 * enum because the vocabulary is the user's and mixes languages, but free text with no
 * normalisation means "Lacteos" and "lacteos" become two categories and no filter ever finds both.
 */
@Service
@RequiredArgsConstructor
@Transactional
class DefaultGroceryStore implements GroceryStore {

    private final GroceryRepository repository;
    private final Clock clock;

    @Override
    public Grocery add(NewGrocery grocery) {
        return toGrocery(repository.save(toEntity(grocery)));
    }

    @Override
    public List<Grocery> addAll(List<NewGrocery> groceries) {
        return repository.saveAll(groceries.stream().map(this::toEntity).toList())
                .stream()
                .map(this::toGrocery)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Grocery> list(String category) {
        List<GroceryEntity> found = StringUtils.hasText(category)
                ? repository.findByCategoryOrderByNameAscIdAsc(normalised(category))
                : repository.findAllByOrderByCategoryAscNameAscIdAsc();
        return found.stream().map(this::toGrocery).toList();
    }

    @Override
    public int delete(List<Long> ids) {
        List<GroceryEntity> present = repository.findAllById(ids);
        repository.deleteAll(present);
        return present.size();
    }

    private GroceryEntity toEntity(NewGrocery grocery) {
        GroceryEntity entity = new GroceryEntity();
        entity.setName(grocery.name().trim());
        entity.setCategory(normalised(grocery.category()));
        entity.setQuantity(grocery.quantity());
        entity.setPrice(grocery.price());
        entity.setNote(StringUtils.hasText(grocery.note()) ? grocery.note().trim() : null);
        entity.setCreatedAt(clock.instant());
        return entity;
    }

    private Grocery toGrocery(GroceryEntity entity) {
        return new Grocery(entity.getId(), entity.getName(), entity.getCategory(), entity.getQuantity(),
                entity.getPrice(), entity.getNote(), entity.getCreatedAt());
    }

    /** ROOT rather than the default locale: a Turkish JVM would otherwise lower-case "I" to "ı". */
    private String normalised(String category) {
        return category.trim().toLowerCase(Locale.ROOT);
    }
}
