package com.github.yonaprojects.yona.domain.user

import com.github.yonaprojects.yona.domain.support.toSnakeCaseSort
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Optional

interface UserRepository : JpaRepository<User, Long> {
    fun findByLoginId(loginId: String): Optional<User>
    fun findByEmail(email: String): Optional<User>
    fun findByToken(token: String): Optional<User>
    fun findByState(state: UserState): List<User>

    // Update only authentication fields; saving a stale User can restore an old password after a reset.
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """UPDATE User u SET u.password = :newHash, u.passwordSalt = null
           WHERE u.id = :id
             AND (u.password = :storedHash OR (u.password IS NULL AND :storedHash IS NULL))
             AND (u.passwordSalt = :storedSalt OR (u.passwordSalt IS NULL AND :storedSalt IS NULL))"""
    )
    fun replacePasswordIfUnchanged(
        @Param("id") id: Long,
        @Param("storedHash") storedHash: String?,
        @Param("storedSalt") storedSalt: String?,
        @Param("newHash") newHash: String
    ): Int

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """UPDATE User u
           SET u.lockedUntil = CASE WHEN u.failedLoginAttempts + 1 >= :maxAttempts THEN :lockDeadline ELSE u.lockedUntil END,
               u.failedLoginAttempts = u.failedLoginAttempts + 1
           WHERE u.id = :id"""
    )
    fun recordLoginFailure(
        @Param("id") id: Long,
        @Param("maxAttempts") maxAttempts: Int,
        @Param("lockDeadline") lockDeadline: Instant
    ): Int

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """UPDATE User u SET u.failedLoginAttempts = 0, u.lockedUntil = null
           WHERE u.id = :id AND (u.failedLoginAttempts <> 0 OR u.lockedUntil IS NOT NULL)"""
    )
    fun resetLoginFailures(@Param("id") id: Long): Int

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """UPDATE User u
           SET u.name = :name, u.englishName = COALESCE(:englishName, u.englishName), u.isGuest = :isGuest
           WHERE u.id = :id"""
    )
    fun syncLdapProfile(
        @Param("id") id: Long,
        @Param("name") name: String,
        @Param("englishName") englishName: String?,
        @Param("isGuest") isGuest: Boolean
    ): Int

    // JPQL 대신 네이티브 쿼리를 쓰는 이유는 IssueRepository.searchIssues() 주석 참고 (Postgres
    // Hibernate 7.2.x LIKE 2개 이상 버그 회피 — name/login_id(/english_name) 컬럼이 여러 개라
    // 1개로 인수분해 불가). enum(UserState)은 문자열 이름으로 바인딩한다.
    @Query(
        value = "SELECT * FROM n4user WHERE state IN ('ACTIVE', 'SITE_ADMIN') AND (LOWER(name) LIKE LOWER(:keyword) OR LOWER(login_id) LIKE LOWER(:keyword) OR LOWER(english_name) LIKE LOWER(:keyword))",
        countQuery = "SELECT COUNT(*) FROM n4user WHERE state IN ('ACTIVE', 'SITE_ADMIN') AND (LOWER(name) LIKE LOWER(:keyword) OR LOWER(login_id) LIKE LOWER(:keyword) OR LOWER(english_name) LIKE LOWER(:keyword))",
        nativeQuery = true
    )
    fun searchUsersQuery(@Param("keyword") keyword: String, pageable: Pageable): Page<User>

    fun searchUsers(keyword: String, pageable: Pageable): Page<User> =
        searchUsersQuery(keyword, pageable.toSnakeCaseSort())

    @Query(
        value = "SELECT COUNT(*) FROM n4user WHERE state IN ('ACTIVE', 'SITE_ADMIN') AND (LOWER(name) LIKE LOWER(:keyword) OR LOWER(login_id) LIKE LOWER(:keyword) OR LOWER(english_name) LIKE LOWER(:keyword))",
        nativeQuery = true
    )
    fun countSearchUsers(@Param("keyword") keyword: String): Int

    @Query(
        value = "SELECT * FROM n4user WHERE state = :#{#state.name()} AND (LOWER(name) LIKE LOWER(:query) OR LOWER(login_id) LIKE LOWER(:query))",
        countQuery = "SELECT COUNT(*) FROM n4user WHERE state = :#{#state.name()} AND (LOWER(name) LIKE LOWER(:query) OR LOWER(login_id) LIKE LOWER(:query))",
        nativeQuery = true
    )
    fun findUsersForAdminQuery(@Param("state") state: UserState, @Param("query") query: String, pageable: Pageable): Page<User>

    fun findUsersForAdmin(state: UserState, query: String, pageable: Pageable): Page<User> =
        findUsersForAdminQuery(state, query, pageable.toSnakeCaseSort())

    @Query(
        value = "SELECT COUNT(*) FROM n4user WHERE state = :#{#state.name()} AND (LOWER(name) LIKE LOWER(:query) OR LOWER(login_id) LIKE LOWER(:query))",
        nativeQuery = true
    )
    fun countUsersForAdmin(@Param("state") state: UserState, @Param("query") query: String): Int
}
