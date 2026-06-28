package ru.bereznevn.jpa_hibernate_cheet_sheet.demo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.bereznevn.jpa_hibernate_cheet_sheet.entity.Product;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Наглядная демонстрация раздела №1 конспекта «Persistence Context и жизненный цикл entity».
 * <p>
 * Запускается на старте приложения (CommandLineRunner). Границами транзакций управляем
 * ВРУЧНУЮ через {@link TransactionTemplate} — это важно: внутри tx.execute(...) persistence
 * context (PC) ОТКРЫТ, а как только колбэк завершился и транзакция закоммичена — PC ЗАКРЫТ,
 * и сущность становится detached. Именно смена этих границ и делает жизненный цикл видимым.
 * <p>
 * SQL, который Hibernate реально шлёт в БД, виден в консоли благодаря
 * logging.level.org.hibernate.SQL=DEBUG в application.yml — сверяйте вывод println с SQL.
 */
@Component
public class EntityLifeCyclesDemo implements CommandLineRunner {
    /**
     * Общий (shared) EntityManager-прокси: при активной транзакции он делегирует
     * в EntityManager, привязанный к этой транзакции (к её persistence context).
     */
    @PersistenceContext
    private EntityManager em;
    private final TransactionTemplate tx;

    public EntityLifeCyclesDemo(PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(String... args) {
        Long id = demoTransientToManaged();   // 1 + 2: TRANSIENT → MANAGED (+ persist)
        demoDirtyChecking(id);                 // 3: UPDATE без save()
        demoL1IdentityMap(id);                 // 4: кэш 1-го уровня (identity map)
        Product detached = demoDetached(id);   // 5: DETACHED — изменения не сохраняются
        demoMerge(detached);                   // 6: merge возвращает НОВЫЙ managed-инстанс
        demoFlushVsCommit(id);                 // 7: flush vs commit
        demoRemoved(id);                       // 8: REMOVED → DELETE
        banner("Демо жизненного цикла завершено");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1 + 2. TRANSIENT → MANAGED
    // ──────────────────────────────────────────────────────────────────────────
    private Long demoTransientToManaged() {
        banner("1–2. TRANSIENT → MANAGED (persist)");

        // TRANSIENT: объект создан через new, ещё не связан ни с каким PC, id == null, в БД его нет.
        Product product = Product.builder()
                .name("Laptop")
                .price(new BigDecimal("1500.00"))
                .quantity(10)
                .createdAt(Instant.now())
                .build();
        System.out.println("[TRANSIENT] new Product(): id=" + product.getId()
                + " — объект не связан с PC, в БД отсутствует");

        // Открываем транзакцию → PC открыт. Внутри делаем persist.
        Long id = tx.execute(status -> {
            System.out.println("[before persist] em.contains(product) = " + em.contains(product));
            em.persist(product);
            // ВАЖНО: у нас @GeneratedValue(IDENTITY) → INSERT выполняется уже здесь,
            // чтобы получить сгенерированный id (с IDENTITY Hibernate не может отложить вставку).
            System.out.println("[MANAGED] после persist: em.contains(product) = " + em.contains(product)
                    + ", id=" + product.getId() + " — теперь сущность отслеживается PC");
            return product.getId();
        });

        // Транзакция закоммичена → PC закрыт → product стал DETACHED.
        System.out.println("[после commit] product теперь DETACHED (PC закрыт)");
        return id;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. DIRTY CHECKING — UPDATE без вызова save()/persist()
    // ──────────────────────────────────────────────────────────────────────────
    private void demoDirtyChecking(Long id) {
        banner("3. DIRTY CHECKING — UPDATE без save()");

        tx.executeWithoutResult(status -> {
            Product managed = em.find(Product.class, id);   // SELECT → managed + снят snapshot
            System.out.println("[MANAGED] загружен, цена была = " + managed.getPrice());

            managed.setPrice(new BigDecimal("1399.99"));     // просто меняем поле сеттером
            System.out.println("[MANAGED] изменили цену на " + managed.getPrice()
                    + " — НЕ вызываем save()/persist()");
            // На commit Hibernate сравнит поля со snapshot и сам сгенерирует UPDATE.
            System.out.println("→ ждите UPDATE products SET price=... в SQL-логах при commit");
        });
        System.out.println("[после commit] изменение ушло в БД через dirty checking");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. Кэш 1-го уровня (identity map): один id в рамках PC = один и тот же объект
    // ──────────────────────────────────────────────────────────────────────────
    private void demoL1IdentityMap(Long id) {
        banner("4. L1-кэш (identity map): два find() → один объект, один SELECT");

        tx.executeWithoutResult(status -> {
            Product first = em.find(Product.class, id);    // SELECT
            Product second = em.find(Product.class, id);   // из L1 — без SELECT
            System.out.println("first == second ? " + (first == second)
                    + " — второй find() взял объект из кэша 1-го уровня, повторного SELECT нет");
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. DETACHED — изменения detached-сущности в БД не попадают
    // ──────────────────────────────────────────────────────────────────────────
    private Product demoDetached(Long id) {
        banner("5. DETACHED — изменения не сохраняются");

        // Достаём сущность и возвращаем её ИЗ транзакции → за пределами execute она detached.
        Product detached = tx.execute(status -> em.find(Product.class, id));
        System.out.println("[DETACHED] получили сущность за пределами транзакции, name=" + detached.getName());

        detached.setName("ИЗМЕНЕНО ВНЕ ТРАНЗАКЦИИ");  // меняем detached-объект
        System.out.println("[DETACHED] поменяли name на '" + detached.getName()
                + "' — но PC закрыт, dirty checking тут не работает");

        // В новой транзакции перечитываем — убеждаемся, что в БД имя НЕ изменилось.
        tx.executeWithoutResult(status -> {
            Product reloaded = em.find(Product.class, id);
            System.out.println("[проверка из БД] name в базе = '" + reloaded.getName()
                    + "' — изменение detached-объекта НЕ сохранилось");
        });
        return detached;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 6. MERGE — возвращает НОВЫЙ managed-инстанс; переданный остаётся detached
    // ──────────────────────────────────────────────────────────────────────────
    private void demoMerge(Product detached) {
        banner("6. MERGE — возвращает новый managed-инстанс");

        detached.setName("Laptop (merged)");   // меняем detached-копию перед merge

        tx.executeWithoutResult(status -> {
            // merge сделает SELECT (загрузить текущее состояние) + перенесёт изменения,
            // вернёт ДРУГОЙ объект — управляемую копию.
            Product managed = em.merge(detached);
            System.out.println("managed == detached ? " + (managed == detached)
                    + " — merge вернул НОВЫЙ инстанс");
            System.out.println("em.contains(detached) = " + em.contains(detached)
                    + " (переданный остаётся detached), em.contains(managed) = " + em.contains(managed)
                    + " (управляемая копия)");
            System.out.println("→ ждите SELECT + UPDATE в SQL-логах");
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 7. flush vs commit
    // ──────────────────────────────────────────────────────────────────────────
    private void demoFlushVsCommit(Long id) {
        banner("7. flush vs commit");

        tx.executeWithoutResult(status -> {
            Product managed = em.find(Product.class, id);
            managed.setQuantity(99);
            System.out.println("[MANAGED] изменили quantity=99, ещё НЕ flush");

            em.flush();   // SQL UPDATE уходит в БД ПРЯМО СЕЙЧАС, но транзакция ещё не закоммичена
            System.out.println("[flush] UPDATE уже отправлен в БД, но commit ещё не было — "
                    + "изменение можно откатить (rollback). commit() позже зафиксирует его окончательно.");
        });
        System.out.println("[после commit] изменение зафиксировано");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 8. REMOVED — DELETE при commit (попутно чистим демо-данные)
    // ──────────────────────────────────────────────────────────────────────────
    private void demoRemoved(Long id) {
        banner("8. REMOVED → DELETE");

        tx.executeWithoutResult(status -> {
            Product managed = em.find(Product.class, id);
            em.remove(managed);   // помечаем на удаление
            System.out.println("[REMOVED] em.contains после remove = " + em.contains(managed)
                    + " — помечен на удаление, DELETE уйдёт при commit");
        });
        System.out.println("[после commit] строка удалена из БД (демо-данные подчищены)");
    }

    private void banner(String title) {
        System.out.println();
        System.out.println("========== " + title + " ==========");
    }
}
