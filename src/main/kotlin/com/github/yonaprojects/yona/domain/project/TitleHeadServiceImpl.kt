package com.github.yonaprojects.yona.domain.project

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// yona models/TitleHead.java 대응.
@Service
@Transactional
class TitleHeadServiceImpl(
    private val titleHeadRepository: TitleHeadRepository
) : TitleHeadService {

    override fun saveTitleHeadKeyword(project: Project, title: String) {
        com.github.yonaprojects.yona.domain.issue.TitleHeads.extract(title).forEach { newHeadKeyword(project, it) }
    }

    override fun deleteTitleHeadKeyword(project: Project, title: String) {
        com.github.yonaprojects.yona.domain.issue.TitleHeads.extract(title).forEach { reduceHeadKeyword(project, it) }
    }

    override fun search(project: Project, query: String): List<TitleHead> {
        return titleHeadRepository.findByProjectIdAndHeadKeywordContainingIgnoreCase(project.id!!, query)
    }

    private fun newHeadKeyword(project: Project, headKeyword: String) {
        val found = titleHeadRepository.findByProjectIdAndHeadKeyword(project.id!!, headKeyword)
        if (found != null) {
            found.frequency++
            titleHeadRepository.save(found)
        } else {
            titleHeadRepository.save(TitleHead(project = project, headKeyword = headKeyword, frequency = 1))
        }
    }

    // yona TitleHead.reduceHeadKeyword()의 "frequency == 0이면 삭제" 그대로 재현 — 생성/삭제 호출이
    // 짝을 이루지 않는 이례적 상황(예: 데이터 정합성 깨짐)에서 frequency가 음수로 남는 legacy의
    // 관찰 가능한 동작도 그대로 유지한다.
    private fun reduceHeadKeyword(project: Project, headKeyword: String) {
        val found = titleHeadRepository.findByProjectIdAndHeadKeyword(project.id!!, headKeyword) ?: return
        found.frequency--
        if (found.frequency == 0) {
            titleHeadRepository.delete(found)
        } else {
            titleHeadRepository.save(found)
        }
    }

}
