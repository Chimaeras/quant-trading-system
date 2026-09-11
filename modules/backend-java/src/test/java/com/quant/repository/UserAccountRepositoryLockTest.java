package com.quant.repository;

import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R31-P1-2：下单路径的账户行必须带悲观写锁。
 *
 * <p>`SimAccountService.placeOrder` 是「读余额 → 校验 → 扣减 → 保存」的读改写序列，
 * 账户行没有 `@Version` 也没有行锁时，两笔并发委托会各自通过校验并各扣一次。
 * Mockito 测试只验证调用路径，抓不到「注解被删」这类回归，故在此直接断言注解存在。
 */
class UserAccountRepositoryLockTest {

    @Test
    void accountRowQueryIsPessimisticWriteLocked() throws Exception {
        Method method = UserAccountRepository.class.getMethod(
                "findAllForUpdateByUserIdAndStatus", Long.class, Integer.class);

        Lock lock = method.getAnnotation(Lock.class);
        assertThat(lock).as("下单路径依赖悲观写锁，注解不可缺失").isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void readOnlyLookupStaysUnlocked() throws Exception {
        Method method = UserAccountRepository.class.getMethod(
                "findFirstByUserIdAndStatus", Long.class, Integer.class);

        assertThat(method.getAnnotation(Lock.class)).as("只读路径不应加锁").isNull();
    }
}
