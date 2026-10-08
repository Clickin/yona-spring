package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.user.User
import jakarta.persistence.*
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

/** Administrator-confirmed link from a source author to a Yona account. Never inferred from names. */
@Entity
@Table(
    name = "repository_mirror_author",
    uniqueConstraints = [UniqueConstraint(name = "uk_mirror_author_source", columnNames = ["mirror_id", "source_author"])]
)
class RepositoryMirrorAuthor(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mirror_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var mirror: RepositoryMirror = RepositoryMirror(),
    // svn:author for SVN; a Git/Hg mirror would store the commit e-mail here.
    @Column(name = "source_author", nullable = false, length = 255)
    var sourceAuthor: String = "",
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    var user: User = User()
)
