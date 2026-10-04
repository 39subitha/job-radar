package com.jobradar.app

/** Word lists used to read any resume, across engineering, IT, quality, civil, business. */
object Dictionary {
    val SKILLS: List<String> = listOf(
        // mechanical design / manufacturing
        "CATIA", "NX", "Unigraphics", "Creo", "Pro/E", "SolidWorks", "AutoCAD", "Inventor", "Fusion 360", "Teamcenter", "Windchill",
        "Enovia", "3DEXPERIENCE", "PLM", "Delmia", "Process Simulate", "Tecnomatix", "Robcad", "ANSYS", "HyperMesh", "Abaqus",
        "FEA", "CFD", "GD&T", "Tolerance stack-up", "DFM", "DFMEA", "PFMEA", "FMEA", "DFMA", "Sheet metal", "Welding",
        "Welding fixture", "Fixture", "Jig", "Tooling", "Tool design", "Press tool", "Die design", "Mould design", "Mold design",
        "Injection molding", "Casting", "Forging", "Machining", "CNC", "CAM", "Mastercam", "Gauge", "Metrology", "CMM",
        "BIW", "Bogie", "Rolling stock", "Composites", "Hydraulics", "Pneumatics", "Material selection", "Ergonomics",
        "Cost reduction", "Value engineering", "Lean", "Kaizen", "5S", "Six Sigma", "TPM", "SMED", "VSM", "Line balancing",
        "Time study", "Work instructions", "Process planning", "Industrialization", "Assembly line", "Robotics", "Automation",
        "3D printing", "Reverse engineering", "2D drawing", "3D model", "BOM", "ECN", "Design validation", "Prototyping",
        // quality
        "ISO 9001", "IATF 16949", "AS9100", "EN 15085", "ISO 14001", "ISO 45001", "PPAP", "APQP", "SPC", "MSA", "8D",
        "Root cause analysis", "CAPA", "Internal audit", "Supplier quality", "Quality control", "Quality assurance",
        "Minitab", "Control plan", "NDT", "Inspection", "Calibration",
        // electrical / electronics
        "PLC", "SCADA", "HMI", "EPLAN", "AutoCAD Electrical", "MATLAB", "Simulink", "PCB design", "Altium", "OrCAD",
        "Embedded C", "Microcontroller", "Power electronics", "Motor control", "Wiring harness", "Panel design", "VFD",
        "LabVIEW", "Instrumentation", "Arduino", "IoT",
        // civil / construction
        "STAAD Pro", "ETABS", "Revit", "BIM", "Civil 3D", "Primavera", "MS Project", "Estimation", "Quantity surveying",
        "Site execution", "Structural design", "Surveying", "Tekla",
        // software / data
        "Java", "Python", "JavaScript", "TypeScript", "C++", "C#", ".NET", "Kotlin", "Swift", "Go", "Rust", "PHP", "Ruby",
        "React", "Angular", "Vue", "Node.js", "Spring Boot", "Django", "Flask", "HTML", "CSS", "SQL", "MySQL", "PostgreSQL",
        "MongoDB", "Oracle", "REST API", "Microservices", "AWS", "Azure", "GCP", "Docker", "Kubernetes", "Git", "Linux",
        "Jenkins", "CI/CD", "DevOps", "Terraform", "Android", "iOS", "Flutter", "Machine learning", "Deep learning",
        "TensorFlow", "PyTorch", "NLP", "Computer vision", "Data analysis", "Data science", "Pandas", "NumPy", "Power BI",
        "Tableau", "Excel", "VBA", "Selenium", "Manual testing", "Automation testing", "JIRA", "Agile", "Scrum",
        // business / operations
        "SAP", "SAP MM", "SAP PP", "ERP", "MES", "CRM", "Salesforce", "Supply chain", "Procurement", "Logistics",
        "Inventory management", "Production planning", "Project management", "PMP", "Budgeting", "Vendor management",
        "Tally", "GST", "Accounting", "Digital marketing", "SEO",
    )

    /** Industries / domains. */
    val DOMAINS: List<String> = listOf(
        "Railway", "Rail", "Rolling stock", "Metro", "Locomotive", "Automotive", "Aerospace", "Defence", "Defense", "Marine",
        "Shipbuilding", "Oil & Gas", "Energy", "Power", "Renewable", "Wind", "Solar", "Nuclear", "Semiconductor", "Electronics",
        "Telecom", "Healthcare", "Medical devices", "Pharma", "FMCG", "Construction", "Infrastructure", "Banking", "Finance",
        "Insurance", "E-commerce", "Retail", "Logistics", "Manufacturing", "Heavy equipment", "Agriculture", "Steel", "Mining",
    )

    /** Last word of a job title. */
    val ROLE_NOUNS = listOf(
        "Engineer", "Designer", "Developer", "Manager", "Analyst", "Technician", "Consultant", "Architect", "Specialist",
        "Scientist", "Administrator", "Accountant", "Executive", "Officer", "Supervisor", "Inspector", "Draftsman", "Planner",
        "Coordinator", "Tester", "Lead", "Programmer", "Associate", "Trainee", "Intern",
    )

    /** Words that say nothing about the kind of job. */
    val GENERIC_TITLE_WORDS = setOf(
        "engineer", "engineers", "senior", "sr", "junior", "jr", "lead", "manager", "specialist", "associate", "staff", "principal",
        "i", "ii", "iii", "iv", "of", "and", "the", "for", "in", "a", "&", "-", "/", "team", "head", "chief", "assistant",
        "executive", "officer", "trainee", "intern", "graduate", "level", "experienced", "global", "consultant",
    )

    val DEGREE_WORDS = listOf(
        "B.E", "B.E.", "BE ", "B.Tech", "BTech", "M.E", "M.Tech", "MTech", "Bachelor", "Master", "MBA", "Diploma", "Ph.D", "PhD",
        "B.Sc", "M.Sc", "BSc", "MSc", "BCA", "MCA", "B.Com", "M.Com", "ITI", "HSC", "SSLC", "12th", "10th",
    )

    val SECTION_HEADERS = mapOf(
        "summary" to listOf("summary", "profile", "objective", "about me", "professional summary", "career objective"),
        "skills" to listOf("skills", "competencies", "technical skills", "core competencies", "key skills", "expertise", "tools"),
        "experience" to listOf("experience", "work experience", "employment", "professional experience", "work history", "career history"),
        "education" to listOf("education", "academic", "qualification", "qualifications"),
        "certifications" to listOf("certification", "certifications", "training", "courses", "training & certifications", "licenses"),
        "languages" to listOf("languages", "language"),
        "projects" to listOf("projects", "key projects", "academic projects"),
        "other" to listOf("additional information", "personal details", "personal information", "declaration", "hobbies", "interests", "achievements", "awards"),
    )

    val COUNTRIES = listOf(
        "India", "South Korea", "Korea", "United States", "USA", "United Kingdom", "UK", "Germany", "France", "Spain", "Italy",
        "Canada", "Mexico", "Brazil", "China", "Japan", "Singapore", "Malaysia", "Thailand", "Vietnam", "Australia",
        "New Zealand", "United Arab Emirates", "UAE", "Dubai", "Saudi Arabia", "Qatar", "Poland", "Netherlands", "Belgium",
        "Switzerland", "Austria", "Sweden", "Ireland", "Czech Republic", "Hungary", "Romania", "Portugal",
    )
}
